CREATE TABLE sf_user (
  id uuid PRIMARY KEY, username varchar(64) NOT NULL UNIQUE,
  password_hash varchar(100) NOT NULL, enabled boolean NOT NULL DEFAULT true
);
CREATE TABLE sf_project (id uuid PRIMARY KEY, name varchar(120) NOT NULL);
CREATE TABLE sf_project_member (
  project_id uuid REFERENCES sf_project(id), user_id uuid REFERENCES sf_user(id),
  role varchar(16) NOT NULL CHECK (role IN ('OWNER','READER')),
  PRIMARY KEY (project_id,user_id)
);
CREATE TABLE sf_task (
  id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES sf_project(id),
  created_by uuid NOT NULL REFERENCES sf_user(id), idempotency_key varchar(128) NOT NULL,
  request_hash char(64) NOT NULL, request_json jsonb NOT NULL,
  state varchar(16) NOT NULL CHECK (state IN ('QUEUED','RUNNING','VERIFYING','SUCCEEDED','FAILED','CANCELLED')),
  event_seq bigint NOT NULL DEFAULT 0, fencing_token bigint NOT NULL DEFAULT 0,
  attempts integer NOT NULL DEFAULT 0, lease_until timestamptz, deadline_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  error_code varchar(64), UNIQUE(project_id,idempotency_key)
);
CREATE INDEX sf_task_dispatch ON sf_task(state, lease_until, created_at);
CREATE TABLE sf_task_run (
  id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES sf_task(id), fencing_token bigint NOT NULL,
  worker_id varchar(100) NOT NULL, status varchar(24) NOT NULL,
  started_at timestamptz NOT NULL DEFAULT clock_timestamp(), finished_at timestamptz,
  UNIQUE(task_id,fencing_token)
);
CREATE TABLE sf_task_event (
  task_id uuid NOT NULL REFERENCES sf_task(id), sequence bigint NOT NULL,
  type varchar(64) NOT NULL, payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(task_id,sequence)
);
CREATE TABLE sf_outbox (
  id uuid PRIMARY KEY, task_id uuid NOT NULL REFERENCES sf_task(id), type varchar(64) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), delivered_at timestamptz
);
CREATE TABLE sf_tool_call (
  id uuid PRIMARY KEY, run_id uuid NOT NULL REFERENCES sf_task_run(id), tool_name varchar(64) NOT NULL,
  request_hash char(64) NOT NULL, status varchar(24) NOT NULL,
  started_at timestamptz NOT NULL DEFAULT clock_timestamp(), finished_at timestamptz,
  response_json jsonb, error_code varchar(64)
);
CREATE TABLE sf_task_result (
  task_id uuid PRIMARY KEY REFERENCES sf_task(id), run_id uuid NOT NULL REFERENCES sf_task_run(id),
  request_hash char(64) NOT NULL, result_hash char(64) NOT NULL, result_json jsonb NOT NULL,
  accepted_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
