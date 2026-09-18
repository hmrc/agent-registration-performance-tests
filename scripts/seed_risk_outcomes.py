#!/usr/bin/env python3
"""
Seed deterministic applicant and individual risk-outcome performance test data.

This script keeps setup separate from the timed Gatling phase. It creates or
completes prerequisite applications first, then runs risking, uploads stable
entity/individual outcomes, processes results, optionally pre-completes control
journeys, and finally writes feeder CSV files.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import re
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from html import unescape
from typing import Iterable
from urllib.parse import quote, urlencode, urljoin

try:
    import requests
except ImportError:
    print("ERROR: 'requests' library is required. Install it with: pip install requests", file=sys.stderr)
    sys.exit(1)


APPLICANT_RESUBMITTED_MARKER = "You have resubmitted your application for an agent services account"
INDIVIDUAL_CONFIRMATION_MARKER = "You have finished this process"
INDIVIDUAL_NONFIXABLE_MARKER = "Records show that you do not meet the registration conditions"
INDIVIDUAL_FIXABLE_MARKER = "You do not meet the registration conditions yet"
INDIVIDUAL_TASKLIST_MARKER = "Take action: You have not met the registration conditions"
APPLICANT_TASKLIST_MARKER = "Take action"


@dataclass(frozen=True)
class ScenarioDefinition:
    scenario_id: str
    kind: str
    business_type: str
    completed_section_slug: str
    linked_individuals: int
    profile: str
    expected_outcome: str
    entity_failures: tuple[str, ...] = ()
    individual_failures_by_index: tuple[tuple[int, tuple[str, ...]], ...] = ()
    requires_initial_submission: bool = False
    post_seed_action: str = "none"
    weight: int = 1


SCENARIOS: tuple[ScenarioDefinition, ...] = (
    ScenarioDefinition(
        scenario_id="APP-ST-FIX-RESUB",
        kind="applicant",
        business_type="sole-trader",
        completed_section_slug="sole-trader-declaration",
        linked_individuals=1,
        profile="main",
        expected_outcome="failed-fixable-resubmission",
        individual_failures_by_index=((0, ("Check_4_1", "Check_10_1")),),
        weight=8,
    ),
    ScenarioDefinition(
        scenario_id="APP-LTD-FIX-RESUB-2",
        kind="applicant",
        business_type="limited-company",
        completed_section_slug="limited-company-declaration",
        linked_individuals=2,
        profile="main",
        expected_outcome="failed-fixable-resubmission",
        entity_failures=("Check_4_1",),
        weight=6,
    ),
    ScenarioDefinition(
        scenario_id="APP-LLP-FIX-RESUB-2",
        kind="applicant",
        business_type="llp",
        completed_section_slug="llp-declaration",
        linked_individuals=2,
        profile="main",
        expected_outcome="failed-fixable-resubmission",
        entity_failures=("Check_4_1",),
        weight=6,
    ),
    ScenarioDefinition(
        scenario_id="APP-GP-FIX-RESUB-2",
        kind="applicant",
        business_type="general-partnership",
        completed_section_slug="general-partnership-declaration",
        linked_individuals=2,
        profile="main",
        expected_outcome="failed-fixable-resubmission",
        entity_failures=("Check_4_1",),
        weight=5,
    ),
    ScenarioDefinition(
        scenario_id="APP-SP-FIX-RESUB-2",
        kind="applicant",
        business_type="scottish-partnership",
        completed_section_slug="scottish-partnership-declaration",
        linked_individuals=2,
        profile="main",
        expected_outcome="failed-fixable-resubmission",
        entity_failures=("Check_4_1",),
        weight=5,
    ),
    ScenarioDefinition(
        scenario_id="APP-LP-FIX-RESUB-2",
        kind="applicant",
        business_type="limited-partnership",
        completed_section_slug="limited-partnership-declaration",
        linked_individuals=2,
        profile="main",
        expected_outcome="failed-fixable-resubmission",
        entity_failures=("Check_4_1",),
        weight=5,
    ),
    ScenarioDefinition(
        scenario_id="APP-SLP-FIX-RESUB-2",
        kind="applicant",
        business_type="scottish-limited-partnership",
        completed_section_slug="scottish-limited-partnership-declaration",
        linked_individuals=2,
        profile="main",
        expected_outcome="failed-fixable-resubmission",
        entity_failures=("Check_4_1",),
        weight=5,
    ),
    ScenarioDefinition(
        scenario_id="APP-AMLS-FIX-RESUB",
        kind="applicant",
        business_type="limited-company",
        completed_section_slug="limited-company-declaration",
        linked_individuals=2,
        profile="main",
        expected_outcome="failed-fixable-amls-resubmission",
        entity_failures=("Check_3_1",),
        weight=4,
    ),
    ScenarioDefinition(
        scenario_id="APP-RESUBMITTED-STATUS",
        kind="applicant",
        business_type="llp",
        completed_section_slug="llp-declaration",
        linked_individuals=2,
        profile="control",
        expected_outcome="already-resubmitted-status",
        entity_failures=("Check_4_1",),
        post_seed_action="applicant_resubmit",
        weight=1,
    ),
    ScenarioDefinition(
        scenario_id="IND-FIX-DETAILS",
        kind="individual",
        business_type="llp",
        completed_section_slug="llp-declaration",
        linked_individuals=2,
        profile="main",
        expected_outcome="failed-fixable-details",
        individual_failures_by_index=((0, ("Check_4_1", "Check_10_1")),),
        weight=8,
    ),
    ScenarioDefinition(
        scenario_id="IND-FIX-CONFIRM-ONLY",
        kind="individual",
        business_type="limited-company",
        completed_section_slug="limited-company-declaration",
        linked_individuals=2,
        profile="main",
        expected_outcome="failed-fixable-confirm-only",
        individual_failures_by_index=((0, ("Check_4_1",)),),
        weight=6,
    ),
    ScenarioDefinition(
        scenario_id="IND-FIX-ALREADY-CONFIRMED",
        kind="individual",
        business_type="llp",
        completed_section_slug="llp-declaration",
        linked_individuals=2,
        profile="control",
        expected_outcome="failed-fixable-already-confirmed",
        individual_failures_by_index=((0, ("Check_4_1",)),),
        post_seed_action="individual_complete",
        weight=1,
    ),
    ScenarioDefinition(
        scenario_id="IND-NONFIXABLE-CONTROL",
        kind="individual",
        business_type="limited-company",
        completed_section_slug="limited-company-declaration",
        linked_individuals=2,
        profile="control",
        expected_outcome="failed-non-fixable-control",
        individual_failures_by_index=((0, ("Check_6",)),),
        weight=1,
    ),
    ScenarioDefinition(
        scenario_id="IND-APPROVED-CONTROL",
        kind="individual",
        business_type="llp",
        completed_section_slug="llp-declaration",
        linked_individuals=2,
        profile="control",
        expected_outcome="approved-control",
        weight=1,
    ),
    ScenarioDefinition(
        scenario_id="APP-LTD-FIX-RESUB-6",
        kind="applicant",
        business_type="limited-company",
        completed_section_slug="limited-company-partners-and-other-relevant-tax-advisers6",
        linked_individuals=6,
        profile="scale",
        expected_outcome="failed-fixable-resubmission-scale",
        entity_failures=("Check_4_1",),
        requires_initial_submission=True,
        weight=3,
    ),
    ScenarioDefinition(
        scenario_id="APP-LLP-FIX-RESUB-6",
        kind="applicant",
        business_type="llp",
        completed_section_slug="llp-partners-and-other-relevant-tax-advisers6",
        linked_individuals=6,
        profile="scale",
        expected_outcome="failed-fixable-resubmission-scale",
        entity_failures=("Check_4_1",),
        requires_initial_submission=True,
        weight=3,
    ),
    ScenarioDefinition(
        scenario_id="APP-GP-FIX-RESUB-6",
        kind="applicant",
        business_type="general-partnership",
        completed_section_slug="general-partnership-partners-and-other-relevant-tax-advisers6",
        linked_individuals=6,
        profile="scale",
        expected_outcome="failed-fixable-resubmission-scale",
        entity_failures=("Check_4_1",),
        requires_initial_submission=True,
        weight=2,
    ),
)

APPLICANT_HEADERS = [
    "scenario_id",
    "business_type",
    "application_reference",
    "applicant_login_url",
    "applicant_start_url",
    "expected_outcome",
    "task_list_url",
    "status_url",
    "declaration_url",
    "entity_failure_url",
    "entity_failure_code",
    "individual_failure_url",
    "individual_failure_code",
    "individual_identity_url",
    "individual_dob_url",
    "individual_nino_url",
    "individual_sautr_url",
    "individual_check_your_answers_url",
    "amls_failure_url",
    "amls_failure_code",
    "amls_supervisor_url",
    "amls_registration_url",
    "amls_evidence_url",
    "amls_check_your_answers_url",
    "parent_individual_count",
]

INDIVIDUAL_HEADERS = [
    "scenario_id",
    "business_type",
    "application_reference",
    "person_reference",
    "link_id",
    "individual_name",
    "individual_login_url",
    "individual_start_url",
    "expected_outcome",
    "task_list_url",
    "failure_details_url",
    "failure_code",
    "identity_url",
    "dob_url",
    "nino_url",
    "sautr_url",
    "check_your_answers_url",
    "declaration_url",
    "confirmation_url",
    "parent_individual_count",
]


@dataclass
class ApplicationSeed:
    scenario: ScenarioDefinition
    pool: str
    record_index: int
    app_id: str
    application_reference: str
    link_id: str
    applicant_user_id: str
    applicant_planet_id: str
    individuals: list[dict]


class Seeder:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.frontend = args.frontend_url.rstrip("/")
        self.backend = args.backend_url.rstrip("/")
        self.stubs = args.stubs_url.rstrip("/")
        self.timeout = 30
        self.sequence = 0
        self.run_id = str(int(time.time() * 1000))

    def next_filename(self, prefix: str) -> str:
        self.sequence += 1
        return f"{prefix}-{self.run_id}-{self.sequence:05d}.json"

    def get_json(self, url: str) -> dict | list:
        response = requests.get(url, timeout=self.timeout)
        response.raise_for_status()
        return response.json()

    def post_form(self, url: str, data: dict | list[tuple[str, str]], allow_redirects: bool = False) -> requests.Response:
        response = requests.post(url, data=data, timeout=self.timeout, allow_redirects=allow_redirects)
        response.raise_for_status()
        return response

    def unwrap(self, value: object) -> str:
        if isinstance(value, dict) and "value" in value:
            nested = value["value"]
            if isinstance(nested, str):
                return nested
        if isinstance(value, str):
            return value
        if value is None:
            return ""
        return str(value)

    def parse_location(self, response: requests.Response) -> str:
        location = response.headers.get("Location")
        if not location:
            raise RuntimeError(f"Expected redirect location from {response.request.method} {response.request.url}")
        return urljoin(response.request.url, unescape(location))

    def extract_csrf(self, body: str) -> str:
        match = re.search(r'name=["\']csrfToken["\']\s+value=["\']([^"\']+)', body)
        if not match:
            raise RuntimeError("Could not extract csrfToken from page")
        return match.group(1)

    def extract_form_action(self, body: str, current_url: str) -> str:
        match = re.search(r'<form[^>]*action=["\']([^"\']+)', body)
        if not match:
            raise RuntimeError(f"Could not extract form action from {current_url}")
        return urljoin(current_url, unescape(match.group(1)))

    def ensure_contains(self, response: requests.Response, marker: str, description: str) -> None:
        if marker not in response.text:
            raise RuntimeError(
                f"Unexpected content for {description} at {response.url}. Expected to find marker: {marker!r}"
            )

    def wait_for(self, description: str, predicate, attempts: int = 60, delay: float = 2.0) -> None:
        last_error: Exception | None = None
        for _ in range(attempts):
            try:
                if predicate():
                    return
                last_error = None
            except Exception as exc:  # pragma: no cover - defensive retry path
                last_error = exc
            time.sleep(delay)
        if last_error:
            raise RuntimeError(f"Timed out waiting for {description}: {last_error}")
        raise RuntimeError(f"Timed out waiting for {description}")

    def applicant_login_url(self, app_id: str, redirect_url: str) -> str:
        user_id = quote(f"applicant_{app_id}", safe="")
        planet_id = quote(f"MMTAR_{app_id}", safe="")
        redirect = quote(redirect_url, safe="")
        return f"{self.frontend}/agent-registration/test-only/find-and-log-in-applicant/{user_id}/{planet_id}?redirectUrl={redirect}"

    def individual_login_url(self, app_id: str, individual_id: str, individual_name: str, redirect_url: str) -> str:
        user_id = quote(f"individual_{individual_id}", safe="")
        planet_id = quote(f"MMTAR_{app_id}", safe="")
        name = quote(individual_name, safe="")
        redirect = quote(redirect_url, safe="")
        return f"{self.frontend}/agent-registration/test-only/find-or-create-and-log-in-individual/{user_id}/{planet_id}/{name}?redirectUrl={redirect}"

    def fast_forward_application(self, completed_section_slug: str) -> str:
        url = f"{self.frontend}/agent-registration/test-only/fast-forward-to/{completed_section_slug}"
        response = requests.get(url, timeout=self.timeout, allow_redirects=False)
        response.raise_for_status()
        location = response.headers.get("Location", "")
        match = re.search(r"/agent-registration/test-only/show-agent-application-tile/([^/?#]+)", location)
        if not match:
            raise RuntimeError(f"Could not extract agentApplicationId from fast-forward redirect: {location}")
        return match.group(1)

    def find_application(self, app_id: str) -> dict:
        return self.get_json(f"{self.backend}/agent-registration/test-only/application/by-agent-application-id/{app_id}")

    def find_individuals(self, app_id: str) -> list[dict]:
        result = self.get_json(f"{self.backend}/agent-registration/test-only/individuals/by-agent-application-id/{app_id}")
        if not isinstance(result, list):
            raise RuntimeError(f"Expected individual list for app {app_id}")
        return result

    def application_reference_exists_for_risking(self, application_reference: str) -> bool:
        url = f"{self.frontend}/agent-registration/test-only/risking/application-for-risking/{quote(application_reference, safe='')}"
        response = requests.get(url, timeout=self.timeout)
        response.raise_for_status()
        return "No application-for-risking found" not in response.text

    def person_reference_exists_for_risking(self, person_reference: str) -> bool:
        url = f"{self.frontend}/agent-registration/test-only/risking/individual-for-risking/{quote(person_reference, safe='')}"
        response = requests.get(url, timeout=self.timeout)
        response.raise_for_status()
        return "No individual-for-risking found" not in response.text

    def completed_risking_exists(self, application_reference: str) -> bool:
        url = f"{self.frontend}/agent-registration/test-only/risking/completed-risking/{quote(application_reference, safe='')}"
        response = requests.get(url, timeout=self.timeout)
        response.raise_for_status()
        return "No completed-risking found" not in response.text

    def application_outcome_type(self, app_payload: dict) -> str:
        outcome = app_payload.get("riskingOutcomeApplication")
        if isinstance(outcome, dict):
            return self.unwrap(outcome.get("outcome"))
        return ""

    def individual_outcome_type(self, individual_payload: dict) -> str:
        outcome = individual_payload.get("riskingOutcomeIndividual")
        if isinstance(outcome, dict):
            return self.unwrap(outcome.get("type"))
        return ""

    def expected_individual_outcome_type(self, failure_codes: Iterable[str]) -> str:
        codes = tuple(failure_codes)
        if any(code.startswith("Check_6") for code in codes):
            return "FailedNonFixable"
        if codes:
            return "FailedFixable"
        return "Approved"

    def expected_application_outcome_type(self, scenario: ScenarioDefinition) -> str:
        if scenario.entity_failures:
            return self.expected_individual_outcome_type(scenario.entity_failures)
        configured_failures = [code for _, failures in scenario.individual_failures_by_index for code in failures]
        return self.expected_individual_outcome_type(configured_failures)

    def wait_for_expected_outcomes(self, seeds: list[ApplicationSeed]) -> None:
        for seed in seeds:
            expected_app_outcome = self.expected_application_outcome_type(seed.scenario)

            def application_matches() -> bool:
                app_payload = self.find_application(seed.app_id)
                return self.application_outcome_type(app_payload) == expected_app_outcome

            self.wait_for(
                f"application outcome {seed.application_reference} -> {expected_app_outcome}",
                application_matches,
            )

            configured_failures = dict(seed.scenario.individual_failures_by_index)
            for index, individual in enumerate(seed.individuals):
                expected_individual_outcome = self.expected_individual_outcome_type(configured_failures.get(index, ()))
                individual_id = self.unwrap(individual.get("_id"))

                def individual_matches() -> bool:
                    individuals = self.find_individuals(seed.app_id)
                    matched = next((candidate for candidate in individuals if self.unwrap(candidate.get("_id")) == individual_id), None)
                    if not matched:
                        return False
                    return self.individual_outcome_type(matched) == expected_individual_outcome

                self.wait_for(
                    f"individual outcome {individual_id} -> {expected_individual_outcome}",
                    individual_matches,
                )

    def get_following_frontend_redirects(self, session: requests.Session, url: str, description: str, max_redirects: int = 20) -> requests.Response:
        current_url = url
        for _ in range(max_redirects):
            response = session.get(current_url, timeout=self.timeout, allow_redirects=False)
            if response.status_code not in (302, 303):
                response.raise_for_status()
                return response
            current_url = self.parse_location(response)
            if not current_url.startswith(self.frontend):
                raise RuntimeError(
                    f"Unexpected redirect for {description} to {current_url}. "
                    "This usually means the application is still Approved (so login is sending the user to ASA) "
                    "or the signed-in user already has an ASA enrolment."
                )
        raise RuntimeError(f"Too many redirects while loading {description} from {url}")

    def run_risking(self) -> None:
        response = requests.get(f"{self.frontend}/agent-registration/test-only/risking/run", timeout=self.timeout)
        response.raise_for_status()

    def run_results_processing(self) -> None:
        response = requests.get(
            f"{self.frontend}/agent-registration/test-only/risking/run-results-file-processing",
            timeout=self.timeout,
        )
        response.raise_for_status()

    def submit_entity_failures(self, application_reference: str, failures: Iterable[str], file_name: str) -> None:
        query = urlencode({"fileName": file_name})
        url = f"{self.frontend}/agent-registration/test-only/risking/select-entity-failures/{quote(application_reference, safe='')}?{query}"
        data = [("failures[]", failure) for failure in failures]
        response = requests.post(url, data=data, timeout=self.timeout, allow_redirects=False)
        if response.status_code not in (302, 303):
            response.raise_for_status()
            raise RuntimeError(f"Unexpected entity failure response status {response.status_code} for {application_reference}")

    def submit_individual_failures(self, person_reference: str, failures: Iterable[str], file_name: str) -> None:
        query = urlencode({"fileName": file_name})
        url = f"{self.frontend}/agent-registration/test-only/risking/select-individual-failures/{quote(person_reference, safe='')}?{query}"
        data = [("failures[]", failure) for failure in failures]
        response = requests.post(url, data=data, timeout=self.timeout, allow_redirects=False)
        if response.status_code not in (302, 303):
            response.raise_for_status()
            raise RuntimeError(f"Unexpected individual failure response status {response.status_code} for {person_reference}")

    def complete_initial_individual_submission(self, app_id: str, link_id: str, individual: dict) -> None:
        individual_id = self.unwrap(individual.get("_id"))
        individual_name = self.unwrap(individual.get("individualName"))
        if not individual_id or not individual_name:
            raise RuntimeError(f"Missing individual identifiers for app {app_id}: {individual}")

        match_url = f"{self.frontend}/agent-registration/provide-details/match-application/{link_id}"
        login_url = self.individual_login_url(app_id, individual_id, individual_name, match_url)
        session = requests.Session()

        response = session.get(login_url, timeout=self.timeout, allow_redirects=True)
        response.raise_for_status()
        self.ensure_contains(response, "confirmMatchToIndividualProvidedDetails", "initial match page")
        csrf = self.extract_csrf(response.text)
        post_response = session.post(
            response.url,
            data={
                "csrfToken": csrf,
                "confirmMatchToIndividualProvidedDetails": "Yes",
                "submit": "SaveAndContinue",
            },
            timeout=self.timeout,
            allow_redirects=False,
        )
        if post_response.status_code not in (302, 303):
            post_response.raise_for_status()
        cya_url = self.parse_location(post_response)

        phone_redirect = session.get(cya_url, timeout=self.timeout, allow_redirects=False)
        if phone_redirect.status_code not in (302, 303):
            phone_redirect.raise_for_status()
        phone_url = self.parse_location(phone_redirect)

        phone_page = session.get(phone_url, timeout=self.timeout)
        phone_page.raise_for_status()
        phone_action = self.extract_form_action(phone_page.text, phone_page.url)
        phone_csrf = self.extract_csrf(phone_page.text)
        phone_post = session.post(
            phone_action,
            data={
                "csrfToken": phone_csrf,
                "individualTelephoneNumber": "07777777777",
                "submit": "SaveAndContinue",
            },
            timeout=self.timeout,
            allow_redirects=False,
        )
        if phone_post.status_code not in (302, 303):
            phone_post.raise_for_status()
        cya_after_phone = self.parse_location(phone_post)

        email_redirect = session.get(cya_after_phone, timeout=self.timeout, allow_redirects=False)
        if email_redirect.status_code not in (302, 303):
            email_redirect.raise_for_status()
        email_url = self.parse_location(email_redirect)

        email_page = session.get(email_url, timeout=self.timeout)
        email_page.raise_for_status()
        email_action = self.extract_form_action(email_page.text, email_page.url)
        email_csrf = self.extract_csrf(email_page.text)
        email_address = f"perf-{individual_id[:8]}@example.com"
        email_post = session.post(
            email_action,
            data={
                "csrfToken": email_csrf,
                "individualEmailAddress": email_address,
                "submit": "SaveAndContinue",
            },
            timeout=self.timeout,
            allow_redirects=False,
        )
        if email_post.status_code not in (302, 303):
            email_post.raise_for_status()
        verify_url = self.parse_location(email_post)

        verify_redirect = session.get(verify_url, timeout=self.timeout, allow_redirects=False)
        if verify_redirect.status_code not in (302, 303):
            verify_redirect.raise_for_status()
        cya_after_email = self.parse_location(verify_redirect)

        ucr_redirect = session.get(cya_after_email, timeout=self.timeout, allow_redirects=False)
        if ucr_redirect.status_code not in (302, 303):
            ucr_redirect.raise_for_status()
        ucr_url = self.parse_location(ucr_redirect)

        cya_after_ucr_redirect = session.get(ucr_url, timeout=self.timeout, allow_redirects=False)
        if cya_after_ucr_redirect.status_code not in (302, 303):
            cya_after_ucr_redirect.raise_for_status()
        cya_after_ucr = self.parse_location(cya_after_ucr_redirect)

        approve_redirect = session.get(cya_after_ucr, timeout=self.timeout, allow_redirects=False)
        if approve_redirect.status_code not in (302, 303):
            approve_redirect.raise_for_status()
        approve_url = self.parse_location(approve_redirect)

        approve_page = session.get(approve_url, timeout=self.timeout)
        approve_page.raise_for_status()
        approve_action = self.extract_form_action(approve_page.text, approve_page.url)
        approve_csrf = self.extract_csrf(approve_page.text)
        approve_post = session.post(
            approve_action,
            data={"csrfToken": approve_csrf, "submit": "SaveAndContinue"},
            timeout=self.timeout,
            allow_redirects=False,
        )
        if approve_post.status_code not in (302, 303):
            approve_post.raise_for_status()
        standards_url = self.parse_location(approve_post)

        standards_page = session.get(standards_url, timeout=self.timeout)
        standards_page.raise_for_status()
        standards_action = self.extract_form_action(standards_page.text, standards_page.url)
        standards_csrf = self.extract_csrf(standards_page.text)
        standards_post = session.post(
            standards_action,
            data={"csrfToken": standards_csrf, "submit": "SaveAndContinue"},
            timeout=self.timeout,
            allow_redirects=False,
        )
        if standards_post.status_code not in (302, 303):
            standards_post.raise_for_status()
        final_cya_url = self.parse_location(standards_post)

        final_cya_page = session.get(final_cya_url, timeout=self.timeout)
        final_cya_page.raise_for_status()
        final_cya_action = self.extract_form_action(final_cya_page.text, final_cya_page.url)
        final_cya_csrf = self.extract_csrf(final_cya_page.text)
        final_cya_post = session.post(
            final_cya_action,
            data={"csrfToken": final_cya_csrf, "submit": "SaveAndContinue"},
            timeout=self.timeout,
            allow_redirects=False,
        )
        if final_cya_post.status_code not in (302, 303):
            final_cya_post.raise_for_status()
        confirmation_url = self.parse_location(final_cya_post)

        confirmation_page = session.get(confirmation_url, timeout=self.timeout)
        confirmation_page.raise_for_status()
        self.ensure_contains(confirmation_page, INDIVIDUAL_CONFIRMATION_MARKER, "initial individual confirmation")

    def submit_scale_application_if_needed(self, scenario: ScenarioDefinition, app_id: str, link_id: str, individuals: list[dict]) -> None:
        if not scenario.requires_initial_submission:
            return
        print(f"      completing initial six-person submission for {scenario.scenario_id} ...", flush=True)
        for individual in individuals:
            self.complete_initial_individual_submission(app_id, link_id, individual)
        declaration_url = f"{self.frontend}/agent-registration/apply/agent-declaration/confirm-declaration"
        login_url = self.applicant_login_url(app_id, declaration_url)
        session = requests.Session()
        declaration_page = session.get(login_url, timeout=self.timeout, allow_redirects=True)
        declaration_page.raise_for_status()
        if "Declaration" not in declaration_page.text:
            task_list_url = f"{self.frontend}/agent-registration/apply/task-list"
            task_list_page = session.get(self.applicant_login_url(app_id, task_list_url), timeout=self.timeout, allow_redirects=True)
            task_list_page.raise_for_status()
            declaration_page = session.get(declaration_url, timeout=self.timeout)
            declaration_page.raise_for_status()
        self.ensure_contains(declaration_page, "Declaration", "initial applicant declaration")
        action = self.extract_form_action(declaration_page.text, declaration_page.url)
        csrf = self.extract_csrf(declaration_page.text)
        post = session.post(
            action,
            data={"csrfToken": csrf, "submit": "AcceptAndSend"},
            timeout=self.timeout,
            allow_redirects=False,
        )
        if post.status_code not in (302, 303):
            post.raise_for_status()
        status_page = session.get(self.parse_location(post), timeout=self.timeout)
        status_page.raise_for_status()

    def create_application_seed(self, scenario: ScenarioDefinition, pool: str, record_index: int) -> ApplicationSeed:
        app_id = self.fast_forward_application(scenario.completed_section_slug)
        app_payload = self.find_application(app_id)
        link_id = self.unwrap(app_payload.get("linkId"))
        application_reference = self.unwrap(app_payload.get("applicationReference"))
        if not link_id or not application_reference:
            raise RuntimeError(f"Application {app_id} missing link_id or application_reference")
        individuals = self.find_individuals(app_id)
        if len(individuals) < scenario.linked_individuals:
            raise RuntimeError(
                f"Expected at least {scenario.linked_individuals} individuals for {scenario.scenario_id}, found {len(individuals)}"
            )
        individuals = individuals[:scenario.linked_individuals]
        self.submit_scale_application_if_needed(scenario, app_id, link_id, individuals)
        return ApplicationSeed(
            scenario=scenario,
            pool=pool,
            record_index=record_index,
            app_id=app_id,
            application_reference=application_reference,
            link_id=link_id,
            applicant_user_id=f"applicant_{app_id}",
            applicant_planet_id=f"MMTAR_{app_id}",
            individuals=individuals,
        )

    def upload_outcomes(self, seed: ApplicationSeed) -> None:
        entity_file = self.next_filename(f"{seed.pool}-{seed.scenario.scenario_id}-entity")
        self.submit_entity_failures(seed.application_reference, seed.scenario.entity_failures, entity_file)

        configured = {index: failures for index, failures in seed.scenario.individual_failures_by_index}
        for index, individual in enumerate(seed.individuals):
            person_reference = self.unwrap(individual.get("personReference"))
            if not person_reference:
                raise RuntimeError(f"Missing personReference for application {seed.app_id}")
            failures = configured.get(index, ())
            individual_file = self.next_filename(f"{seed.pool}-{seed.scenario.scenario_id}-individual-{index + 1}")
            self.submit_individual_failures(person_reference, failures, individual_file)

    def wait_for_risking_inputs(self, seeds: list[ApplicationSeed]) -> None:
        for seed in seeds:
            self.wait_for(
                f"application-for-risking {seed.application_reference}",
                lambda application_reference=seed.application_reference: self.application_reference_exists_for_risking(application_reference),
            )
            for individual in seed.individuals:
                person_reference = self.unwrap(individual.get("personReference"))
                self.wait_for(
                    f"individual-for-risking {person_reference}",
                    lambda person_reference=person_reference: self.person_reference_exists_for_risking(person_reference),
                )

    def wait_for_completed_risking(self, seeds: list[ApplicationSeed]) -> None:
        for seed in seeds:
            self.wait_for(
                f"completed-risking {seed.application_reference}",
                lambda application_reference=seed.application_reference: self.completed_risking_exists(application_reference),
            )

    def complete_applicant_resubmission(self, row: dict[str, str]) -> None:
        session = requests.Session()
        start_page = self.get_following_frontend_redirects(session, row["applicant_login_url"], "applicant login")
        task_list_page = start_page
        self.ensure_contains(task_list_page, APPLICANT_TASKLIST_MARKER, "applicant fixable task list")

        if row.get("entity_failure_url"):
            failure_page = session.get(row["entity_failure_url"], timeout=self.timeout)
            failure_page.raise_for_status()
            action = self.extract_form_action(failure_page.text, failure_page.url)
            csrf = self.extract_csrf(failure_page.text)
            failure_post = session.post(
                action,
                data={"csrfToken": csrf, "isFixed": "Yes"},
                timeout=self.timeout,
                allow_redirects=False,
            )
            if failure_post.status_code not in (302, 303):
                failure_post.raise_for_status()

        declaration_page = session.get(row["declaration_url"], timeout=self.timeout)
        declaration_page.raise_for_status()
        self.ensure_contains(declaration_page, "Declaration", "applicant declaration page")
        declaration_action = self.extract_form_action(declaration_page.text, declaration_page.url)
        declaration_csrf = self.extract_csrf(declaration_page.text)
        declaration_post = session.post(
            declaration_action,
            data={"csrfToken": declaration_csrf, "submit": "AcceptAndSend"},
            timeout=self.timeout,
            allow_redirects=False,
        )
        if declaration_post.status_code not in (302, 303):
            declaration_post.raise_for_status()
        status_page = session.get(self.parse_location(declaration_post), timeout=self.timeout)
        status_page.raise_for_status()
        self.ensure_contains(status_page, APPLICANT_RESUBMITTED_MARKER, "already resubmitted status page")

    def complete_individual_fixable_journey(self, row: dict[str, str]) -> None:
        session = requests.Session()
        landing = session.get(row["individual_login_url"], timeout=self.timeout, allow_redirects=True)
        landing.raise_for_status()

        if row.get("failure_details_url"):
            failure_page = session.get(row["failure_details_url"], timeout=self.timeout)
            failure_page.raise_for_status()
            action = self.extract_form_action(failure_page.text, failure_page.url)
            csrf = self.extract_csrf(failure_page.text)
            post = session.post(
                action,
                data={"csrfToken": csrf, "isFixed": "Yes"},
                timeout=self.timeout,
                allow_redirects=False,
            )
            if post.status_code not in (302, 303):
                post.raise_for_status()

        if row.get("identity_url"):
            identity_page = session.get(row["identity_url"], timeout=self.timeout)
            identity_page.raise_for_status()
            for page_url, payload in (
                (row["dob_url"], {"dateOfBirth.day": "24", "dateOfBirth.month": "04", "dateOfBirth.year": "2006"}),
                (row["nino_url"], {"individualNino.hasNino": "Yes", "individualNino.nino": "AA123456A"}),
                (row["sautr_url"], {"individualSaUtr.hasSaUtr": "Yes", "individualSaUtr.saUtr": "1234567890"}),
            ):
                page = session.get(page_url, timeout=self.timeout)
                page.raise_for_status()
                action = self.extract_form_action(page.text, page.url)
                csrf = self.extract_csrf(page.text)
                data = {"csrfToken": csrf, "submit": "SaveAndContinue", **payload}
                post = session.post(action, data=data, timeout=self.timeout, allow_redirects=False)
                if post.status_code not in (302, 303):
                    post.raise_for_status()

            cya_page = session.get(row["check_your_answers_url"], timeout=self.timeout)
            cya_page.raise_for_status()
            cya_action = self.extract_form_action(cya_page.text, cya_page.url)
            cya_csrf = self.extract_csrf(cya_page.text)
            cya_post = session.post(
                cya_action,
                data={"csrfToken": cya_csrf, "submit": "SaveAndContinue"},
                timeout=self.timeout,
                allow_redirects=False,
            )
            if cya_post.status_code not in (302, 303):
                cya_post.raise_for_status()

        declaration_page = session.get(row["declaration_url"], timeout=self.timeout)
        declaration_page.raise_for_status()
        self.ensure_contains(declaration_page, "You are about to submit your responses", "individual declaration page")
        declaration_action = self.extract_form_action(declaration_page.text, declaration_page.url)
        declaration_csrf = self.extract_csrf(declaration_page.text)
        declaration_post = session.post(
            declaration_action,
            data={"csrfToken": declaration_csrf, "submit": "AcceptAndSend"},
            timeout=self.timeout,
            allow_redirects=False,
        )
        if declaration_post.status_code not in (302, 303):
            declaration_post.raise_for_status()
        confirmation_page = session.get(self.parse_location(declaration_post), timeout=self.timeout)
        confirmation_page.raise_for_status()
        self.ensure_contains(confirmation_page, INDIVIDUAL_CONFIRMATION_MARKER, "individual confirmation page")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Seed deterministic agent-registration risk-outcome performance test data")
    parser.add_argument("--backend-url", default="http://localhost:22202")
    parser.add_argument("--frontend-url", default="http://localhost:22201")
    parser.add_argument("--stubs-url", default="http://localhost:9099")
    parser.add_argument("--output-dir", default="src/test/resources/data/risk-outcomes")
    parser.add_argument("--pools", default="load", help="Comma-separated pools to generate, e.g. load or smoke,load")
    parser.add_argument("--total-peak-jps", type=float, default=0.1)
    parser.add_argument("--rampup-minutes", type=int, default=1)
    parser.add_argument("--steady-minutes", type=int, default=8)
    parser.add_argument("--rampdown-minutes", type=int, default=1)
    parser.add_argument("--buffer-percent", type=int, default=20)
    parser.add_argument("--load-main-records", type=int)
    parser.add_argument("--load-control-records", type=int)
    parser.add_argument("--load-scale-records", type=int)
    parser.add_argument("--smoke-records", type=int, default=1)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def pool_list(raw: str) -> list[str]:
    pools = [pool.strip() for pool in raw.split(",") if pool.strip()]
    if not pools:
        raise RuntimeError("At least one pool must be supplied")
    return pools


def effective_seconds(args: argparse.Namespace) -> float:
    return (args.rampup_minutes * 60 / 2) + (args.steady_minutes * 60) + (args.rampdown_minutes * 60 / 2)


def load_records_for(scenario: ScenarioDefinition, args: argparse.Namespace) -> int:
    override = {
        "main": args.load_main_records,
        "control": args.load_control_records,
        "scale": args.load_scale_records,
    }[scenario.profile]
    if override is not None:
        return override

    total_weight = sum(item.weight for item in SCENARIOS)
    peak_jps = args.total_peak_jps * scenario.weight / total_weight
    buffered = peak_jps * effective_seconds(args) * (1 + args.buffer_percent / 100.0)
    minimum = {"main": 2, "control": 1, "scale": 2}[scenario.profile]
    return max(minimum, math.ceil(buffered))


def records_for_pool(scenario: ScenarioDefinition, pool: str, args: argparse.Namespace) -> int:
    if pool == "smoke":
        return max(1, args.smoke_records)
    if pool == "load":
        return load_records_for(scenario, args)
    raise RuntimeError(f"Unsupported pool: {pool}")


def applicant_routes(frontend: str, link_id: str) -> dict[str, str]:
    base = f"{frontend}/agent-registration"
    return {
        "task_list_url": f"{base}/conditions-not-yet-met/task-list",
        "status_url": f"{base}/application-status",
        "declaration_url": f"{base}/conditions-not-yet-met/declaration",
        "entity_failure_url": f"{base}/conditions-not-yet-met/failure-details/EntityFix.4.1",
        "individual_failure_url": f"{base}/conditions-not-yet-met/sole-trader-failure-details/IndividualFix.4.1",
        "individual_identity_url": f"{base}/conditions-not-yet-met/sole-trader/identity",
        "individual_dob_url": f"{base}/conditions-not-yet-met/sole-trader/date-of-birth",
        "individual_nino_url": f"{base}/conditions-not-yet-met/sole-trader/national-insurance-number",
        "individual_sautr_url": f"{base}/conditions-not-yet-met/sole-trader/self-assessment-unique-taxpayer-reference",
        "individual_check_your_answers_url": f"{base}/conditions-not-yet-met/sole-trader/check-your-answers",
        "amls_failure_url": f"{base}/conditions-not-yet-met/anti-money-laundering/failure-details/EntityFix.3.1",
        "amls_supervisor_url": f"{base}/conditions-not-yet-met/anti-money-laundering/supervisor-name",
        "amls_registration_url": f"{base}/conditions-not-yet-met/anti-money-laundering/registration-number",
        "amls_evidence_url": f"{base}/conditions-not-yet-met/anti-money-laundering/evidence",
        "amls_check_your_answers_url": f"{base}/conditions-not-yet-met/anti-money-laundering/check-your-answers",
        "individual_outcome_url": f"{base}/provide-details/outcome-status/{link_id}",
    }


def individual_routes(frontend: str, link_id: str, failure_code: str) -> dict[str, str]:
    base = f"{frontend}/agent-registration"
    return {
        "start_url": f"{base}/provide-details/outcome-status/{link_id}",
        "task_list_url": f"{base}/provide-details/conditions-not-yet-met/task-list/{link_id}",
        "failure_details_url": f"{base}/provide-details/conditions-not-yet-met/failure-details/{failure_code}/{link_id}",
        "identity_url": f"{base}/provide-details/conditions-not-yet-met/identity/{link_id}",
        "dob_url": f"{base}/provide-details/conditions-not-yet-met/date-of-birth/{link_id}",
        "nino_url": f"{base}/provide-details/conditions-not-yet-met/national-insurance-number/{link_id}",
        "sautr_url": f"{base}/provide-details/conditions-not-yet-met/self-assessment-unique-taxpayer-reference/{link_id}",
        "check_your_answers_url": f"{base}/provide-details/conditions-not-yet-met/check-your-answers/{link_id}",
        "declaration_url": f"{base}/provide-details/conditions-not-yet-met/declaration/{link_id}",
        "confirmation_url": f"{base}/provide-details/conditions-not-yet-met/confirmation/{link_id}",
    }


def write_csv(path: str, headers: list[str], rows: list[dict[str, str]]) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=headers)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    args = parse_args()
    pools = pool_list(args.pools)

    print("\nRisk-outcome deterministic seeding")
    print(f"  Backend      : {args.backend_url}")
    print(f"  Frontend     : {args.frontend_url}")
    print(f"  Stubs        : {args.stubs_url}")
    print(f"  Output dir   : {args.output_dir}")
    print(f"  Pools        : {', '.join(pools)}")
    print(f"  Peak JPS     : {args.total_peak_jps}")
    print(f"  Profile      : {args.rampup_minutes}m ramp-up, {args.steady_minutes}m steady, {args.rampdown_minutes}m ramp-down")
    print(f"  Buffer       : {args.buffer_percent}%")

    for scenario in SCENARIOS:
        per_pool = ", ".join(f"{pool}={records_for_pool(scenario, pool, args)}" for pool in pools)
        print(f"  {scenario.scenario_id:<28} {per_pool}")

    if args.dry_run:
        print("\nDry run complete. No API calls made.")
        return

    seeder = Seeder(args)
    readiness_url = f"{args.backend_url.rstrip('/')}/agent-registration/test-only/recent-applications"
    requests.get(readiness_url, timeout=30).raise_for_status()

    manifest: dict[str, dict[str, object]] = {}

    for pool in pools:
        print(f"\n=== Generating pool: {pool} ===")
        pool_dir = os.path.join(args.output_dir, pool)
        os.makedirs(pool_dir, exist_ok=True)

        applicant_rows_by_scenario: dict[str, list[dict[str, str]]] = defaultdict(list)
        individual_rows_by_scenario: dict[str, list[dict[str, str]]] = defaultdict(list)
        applicant_aggregate: list[dict[str, str]] = []
        individual_aggregate: list[dict[str, str]] = []
        controls_aggregate: list[dict[str, str]] = []
        two_person_aggregate: list[dict[str, str]] = []
        six_person_aggregate: list[dict[str, str]] = []
        seeds_to_process: list[ApplicationSeed] = []

        for scenario in SCENARIOS:
            count = records_for_pool(scenario, pool, args)
            for index in range(1, count + 1):
                print(f"  [{pool}] {scenario.scenario_id} {index}/{count}", flush=True)
                seed = seeder.create_application_seed(scenario, pool, index)
                seeds_to_process.append(seed)

        print("\n  Running risking for all newly created records...")
        seeder.run_risking()
        seeder.wait_for_risking_inputs(seeds_to_process)

        print("  Uploading deterministic outcome files...")
        for seed in seeds_to_process:
            seeder.upload_outcomes(seed)

        print("  Running risking results processing...")
        seeder.run_results_processing()
        seeder.wait_for_expected_outcomes(seeds_to_process)

        print("  Building feeder rows...")
        for seed in seeds_to_process:
            scenario = seed.scenario
            routes = applicant_routes(seeder.frontend, seed.link_id)

            if scenario.kind == "applicant":
                row = {
                    "scenario_id": scenario.scenario_id,
                    "business_type": scenario.business_type,
                    "application_reference": seed.application_reference,
                    "applicant_login_url": seeder.applicant_login_url(seed.app_id, routes["task_list_url"] if scenario.post_seed_action != "applicant_resubmit" else routes["task_list_url"]),
                    "applicant_start_url": routes["status_url"] if scenario.post_seed_action == "applicant_resubmit" else routes["task_list_url"],
                    "expected_outcome": scenario.expected_outcome,
                    "task_list_url": routes["task_list_url"],
                    "status_url": routes["status_url"],
                    "declaration_url": routes["declaration_url"],
                    "entity_failure_url": routes["entity_failure_url"] if scenario.entity_failures else "",
                    "entity_failure_code": "EntityFix.4.1" if scenario.entity_failures else "",
                    "individual_failure_url": routes["individual_failure_url"] if scenario.scenario_id == "APP-ST-FIX-RESUB" else "",
                    "individual_failure_code": "IndividualFix.4.1" if scenario.scenario_id == "APP-ST-FIX-RESUB" else "",
                    "individual_identity_url": routes["individual_identity_url"] if scenario.scenario_id == "APP-ST-FIX-RESUB" else "",
                    "individual_dob_url": routes["individual_dob_url"] if scenario.scenario_id == "APP-ST-FIX-RESUB" else "",
                    "individual_nino_url": routes["individual_nino_url"] if scenario.scenario_id == "APP-ST-FIX-RESUB" else "",
                    "individual_sautr_url": routes["individual_sautr_url"] if scenario.scenario_id == "APP-ST-FIX-RESUB" else "",
                    "individual_check_your_answers_url": routes["individual_check_your_answers_url"] if scenario.scenario_id == "APP-ST-FIX-RESUB" else "",
                    "amls_failure_url": routes["amls_failure_url"] if scenario.scenario_id == "APP-AMLS-FIX-RESUB" else "",
                    "amls_failure_code": "EntityFix.3.1" if scenario.scenario_id == "APP-AMLS-FIX-RESUB" else "",
                    "amls_supervisor_url": routes["amls_supervisor_url"] if scenario.scenario_id == "APP-AMLS-FIX-RESUB" else "",
                    "amls_registration_url": routes["amls_registration_url"] if scenario.scenario_id == "APP-AMLS-FIX-RESUB" else "",
                    "amls_evidence_url": routes["amls_evidence_url"] if scenario.scenario_id == "APP-AMLS-FIX-RESUB" else "",
                    "amls_check_your_answers_url": routes["amls_check_your_answers_url"] if scenario.scenario_id == "APP-AMLS-FIX-RESUB" else "",
                    "parent_individual_count": str(scenario.linked_individuals),
                }
                if scenario.post_seed_action == "applicant_resubmit":
                    seeder.complete_applicant_resubmission(row)
                    row["applicant_login_url"] = seeder.applicant_login_url(seed.app_id, routes["status_url"])
                    row["applicant_start_url"] = routes["status_url"]
                applicant_rows_by_scenario[scenario.scenario_id].append(row)
                applicant_aggregate.append(row)
                if scenario.profile == "control":
                    controls_aggregate.append(row)
                if scenario.linked_individuals == 2:
                    two_person_aggregate.append(row)
                if scenario.linked_individuals == 6:
                    six_person_aggregate.append(row)
            else:
                target_failures = dict(scenario.individual_failures_by_index).get(0, ())
                target_individual = seed.individuals[0]
                target_individual_id = seeder.unwrap(target_individual.get("_id"))
                target_person_reference = seeder.unwrap(target_individual.get("personReference"))
                target_name = seeder.unwrap(target_individual.get("individualName"))
                failure_code = "IndividualFix.4.1"
                if any(code.startswith("Check_10") for code in target_failures):
                    failure_code = "IndividualFix.4.1"
                routes_i = individual_routes(seeder.frontend, seed.link_id, failure_code)
                start_url = routes_i["start_url"]
                if scenario.scenario_id == "IND-FIX-ALREADY-CONFIRMED":
                    start_url = routes_i["task_list_url"]
                row = {
                    "scenario_id": scenario.scenario_id,
                    "business_type": scenario.business_type,
                    "application_reference": seed.application_reference,
                    "person_reference": target_person_reference,
                    "link_id": seed.link_id,
                    "individual_name": target_name,
                    "individual_login_url": seeder.individual_login_url(seed.app_id, target_individual_id, target_name, start_url),
                    "individual_start_url": start_url,
                    "expected_outcome": scenario.expected_outcome,
                    "task_list_url": routes_i["task_list_url"],
                    "failure_details_url": routes_i["failure_details_url"] if target_failures else "",
                    "failure_code": failure_code if target_failures else "",
                    "identity_url": routes_i["identity_url"] if any(code.startswith("Check_10") for code in target_failures) else "",
                    "dob_url": routes_i["dob_url"] if any(code.startswith("Check_10") for code in target_failures) else "",
                    "nino_url": routes_i["nino_url"] if any(code.startswith("Check_10") for code in target_failures) else "",
                    "sautr_url": routes_i["sautr_url"] if any(code.startswith("Check_10") for code in target_failures) else "",
                    "check_your_answers_url": routes_i["check_your_answers_url"] if any(code.startswith("Check_10") for code in target_failures) else "",
                    "declaration_url": routes_i["declaration_url"],
                    "confirmation_url": routes_i["confirmation_url"],
                    "parent_individual_count": str(scenario.linked_individuals),
                }
                if scenario.post_seed_action == "individual_complete":
                    seeder.complete_individual_fixable_journey(row)
                    row["individual_login_url"] = seeder.individual_login_url(seed.app_id, target_individual_id, target_name, routes_i["task_list_url"])
                    row["individual_start_url"] = routes_i["task_list_url"]
                individual_rows_by_scenario[scenario.scenario_id].append(row)
                individual_aggregate.append(row)
                if scenario.profile == "control":
                    controls_aggregate.append(row)
                if scenario.linked_individuals == 2:
                    two_person_aggregate.append(row)
                if scenario.linked_individuals == 6:
                    six_person_aggregate.append(row)

        for scenario in SCENARIOS:
            if scenario.kind == "applicant":
                write_csv(os.path.join(pool_dir, f"{scenario.scenario_id}.csv"), APPLICANT_HEADERS, applicant_rows_by_scenario[scenario.scenario_id])
            else:
                write_csv(os.path.join(pool_dir, f"{scenario.scenario_id}.csv"), INDIVIDUAL_HEADERS, individual_rows_by_scenario[scenario.scenario_id])

        write_csv(os.path.join(pool_dir, "applicant-scenarios.csv"), APPLICANT_HEADERS, applicant_aggregate)
        write_csv(os.path.join(pool_dir, "individual-scenarios.csv"), INDIVIDUAL_HEADERS, individual_aggregate)

        control_headers = APPLICANT_HEADERS if all(row.get("applicant_login_url") for row in controls_aggregate if row) else INDIVIDUAL_HEADERS
        # Mixed control files are easier to inspect as JSON; still emit a CSV using superset columns.
        control_superset_headers = sorted({key for row in controls_aggregate for key in row.keys()})
        write_csv(os.path.join(pool_dir, "control-scenarios.csv"), control_superset_headers, controls_aggregate)
        write_csv(os.path.join(pool_dir, "two-person-variants.csv"), sorted({key for row in two_person_aggregate for key in row.keys()}), two_person_aggregate)
        write_csv(os.path.join(pool_dir, "six-person-variants.csv"), sorted({key for row in six_person_aggregate for key in row.keys()}), six_person_aggregate)

        manifest[pool] = {
            "scenarioCounts": {scenario.scenario_id: records_for_pool(scenario, pool, args) for scenario in SCENARIOS},
            "applicantRecords": len(applicant_aggregate),
            "individualRecords": len(individual_aggregate),
            "controlRecords": len(controls_aggregate),
            "twoPersonRecords": len(two_person_aggregate),
            "sixPersonRecords": len(six_person_aggregate),
            "files": sorted(os.listdir(pool_dir)),
        }

        with open(os.path.join(pool_dir, "manifest.json"), "w", encoding="utf-8") as handle:
            json.dump(manifest[pool], handle, indent=2)

    with open(os.path.join(args.output_dir, "manifest.json"), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)

    print("\nDone. Generated deterministic risk-outcome feeder data.")


if __name__ == "__main__":
    main()

