# Sample model answers (QA-03)

Each file is the JSON text a model returns for one prompt. They're written by hand in the
exact shape of the answer record, for prompts like the ones in `../prompt-golden`. The
contract tests (`*ContractTests`) parse every file through `StructuredLlm` with the
production answer record and output check. So a change to a record, a check or the
parser that would reject such answers fails the build.

- `*.json` must be accepted as is, on the first call.
- `invalid-*.json` must be rejected with the problems the test lists. These are the
  messages the model sees when it's asked again.

The nightly live eval (`AiEvalTests`, `.github/workflows/ai-eval.yml`) is what notices
when the model's real answers drift away from these samples.
