#!/usr/bin/env bash
set -euo pipefail

results_dir="${1:-arc-core/build/test-results/test}"
max_failures="${2:-30}"

echo "### Nightly Matrix Test Summary"
echo

if [ ! -d "$results_dir" ]; then
  echo "- junit results directory not found: \`$results_dir\`"
  exit 0
fi

shopt -s nullglob
xml_files=("$results_dir"/TEST-*.xml)
shopt -u nullglob

if [ "${#xml_files[@]}" -eq 0 ]; then
  echo "- no junit xml files found in \`$results_dir\`"
  exit 0
fi

totals="$(
  awk '
    function extract_number_attr(line, attr,    matched) {
      if (match(line, attr "=\"[0-9.]+\"")) {
        matched = substr(line, RSTART, RLENGTH)
        sub(attr "=\"", "", matched)
        sub("\"$", "", matched)
        return matched + 0
      }
      return 0
    }

    /<testsuite / {
      tests += extract_number_attr($0, "tests")
      failures += extract_number_attr($0, "failures")
      errors += extract_number_attr($0, "errors")
      skipped += extract_number_attr($0, "skipped")
      time += extract_number_attr($0, "time")
      suites += 1
    }
    END {
      printf "%d %d %d %d %d %.3f\n", suites, tests, failures, errors, skipped, time
    }
  ' "${xml_files[@]}"
)"

read -r suites tests failures errors skipped total_time <<< "$totals"

echo "- suites: ${suites}"
echo "- tests: ${tests}"
echo "- failures: ${failures}"
echo "- errors: ${errors}"
echo "- skipped: ${skipped}"
echo "- junit reported time: ${total_time}s"

if [ "$failures" -eq 0 ] && [ "$errors" -eq 0 ]; then
  echo
  echo "No failed tests detected."
  exit 0
fi

echo
echo "#### Failed Test Cases (Top ${max_failures})"

awk '
  function extract_attr(line, attr,    re, m) {
    re = attr "=\"[^\"]+\""
    if (match(line, re)) {
      m = substr(line, RSTART, RLENGTH)
      sub(attr "=\"", "", m)
      sub("\"$", "", m)
      return m
    }
    return ""
  }

  /<testcase / {
    current_case = extract_attr($0, "classname") " :: " extract_attr($0, "name")
    in_case = 1
  }

  in_case && (/<failure / || /<error /) {
    if (!(current_case in seen)) {
      print "- " current_case
      seen[current_case] = 1
      printed += 1
    }
    in_case = 0
  }
  END {
    if (printed == 0) {
      print "- unable to parse failed test case names from junit xml"
    }
  }
' "${xml_files[@]}" | head -n "$max_failures"
