#!/usr/bin/env python3
"""Publish JUnit totals and failures as CI annotations, even when log downloads fail."""
import argparse
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def annotation(level, message):
    escaped = str(message)[:6000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::{level}::{escaped}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('reports', type=Path)
    parser.add_argument('--log', type=Path)
    parser.add_argument('--label', default='Editor regression tests')
    args = parser.parse_args()
    tests = failures = skipped = suites = 0
    for path in sorted(args.reports.rglob('*.xml')):
        root = ET.parse(path).getroot()
        for suite in root.iter('testsuite'):
            if suite.find('testsuite') is not None:
                continue
            suites += 1
            tests += int(suite.get('tests', 0))
            failures += int(suite.get('failures', 0)) + int(suite.get('errors', 0))
            skipped += int(suite.get('skipped', 0))
            for case in suite.findall('testcase'):
                problems = case.findall('failure') + case.findall('error')
                for problem in problems:
                    annotation('error', f"{case.get('classname')}.{case.get('name')}: {problem.get('message', '')}\n{problem.text or ''}")
    if suites == 0 or tests == 0:
        annotation('error', f'{args.label}: no JUnit test results were produced.')
        if args.log and args.log.exists():
            lines = args.log.read_text(encoding='utf-8', errors='replace').splitlines()
            errors = [line for line in lines if line.startswith(('e:', 'error:'))]
            annotation('error', '\n'.join(errors[:20] if errors else lines[-80:]))
        return 1
    summary = f'{args.label}: {tests} tests, {failures} failures, {skipped} skipped.'
    annotation('notice', summary)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as out:
            out.write(summary + '\n')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
