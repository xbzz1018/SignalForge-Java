-- One outbound Agent attempt per task. Unknown paid requests are never replayed.
CREATE TABLE sf_agent_run (
  task_id uuid PRIMARY KEY REFERENCES sf_task(id),
  run_id uuid NOT NULL REFERENCES sf_task_run(id),
  input_json text NOT NULL, input_hash char(64) NOT NULL,
  state varchar(16) NOT NULL CHECK (state IN ('SENDING','RETURNED','UNKNOWN')),
  response_json jsonb,
  budget_day date NOT NULL, reserved_tokens integer NOT NULL CHECK (reserved_tokens>=0),
  actual_tokens integer CHECK (actual_tokens>=0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), received_at timestamptz
);
