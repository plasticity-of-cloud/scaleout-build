# Repository workflow-planning rule

For repository-aware build orchestration, AI analysis is performed at most once for each immutable
Git commit SHA. The analysis input must use a source snapshot resolved to that SHA, not a mutable
branch name.

Persist the validated plan keyed by repository identity and commit SHA. Reuse it for later Maven
builds of that exact commit. A new branch checkout only requires new analysis when it resolves to a
different commit SHA.

This rule prevents repeated Bedrock or headless-Kiro analysis costs and preserves a reproducible
link between the source that was analyzed and the Step Functions workflow that executes it.
