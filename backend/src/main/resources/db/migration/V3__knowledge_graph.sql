-- Knowledge collection is independent of research dispatch and only manages sf_ tables.
CREATE TABLE sf_knowledge_snapshot (
  id uuid PRIMARY KEY,
  project_id uuid NOT NULL REFERENCES sf_project(id),
  security_id varchar(16) NOT NULL,
  content_hash char(64) NOT NULL,
  payload_hash char(64) NOT NULL,
  payload text NOT NULL CHECK (jsonb_typeof(payload::jsonb)='object'),
  graph_json jsonb NOT NULL,
  state varchar(16) NOT NULL CHECK (state IN ('PENDING','READY')),
  fetched_at timestamptz NOT NULL,
  published_at timestamptz,
  UNIQUE(project_id,security_id,content_hash),
  UNIQUE(project_id,id)
);
CREATE TABLE sf_knowledge_job (
  id uuid PRIMARY KEY,
  project_id uuid NOT NULL REFERENCES sf_project(id),
  created_by uuid NOT NULL REFERENCES sf_user(id),
  idempotency_key varchar(128) NOT NULL,
  security_id varchar(16) NOT NULL,
  state varchar(16) NOT NULL CHECK (state IN ('QUEUED','COLLECTING','PROJECTING','SUCCEEDED','FAILED','CANCELLED')),
  snapshot_id uuid,
  attempts integer NOT NULL DEFAULT 0,
  fencing_token bigint NOT NULL DEFAULT 0,
  lease_until timestamptz,
  deadline_at timestamptz NOT NULL,
  reused boolean NOT NULL DEFAULT false,
  error_code varchar(64),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(project_id,idempotency_key),
  FOREIGN KEY(project_id,snapshot_id) REFERENCES sf_knowledge_snapshot(project_id,id)
);
CREATE INDEX sf_knowledge_job_dispatch ON sf_knowledge_job(state,lease_until,created_at);
CREATE TABLE sf_knowledge_outbox (
  job_id uuid PRIMARY KEY REFERENCES sf_knowledge_job(id),
  snapshot_id uuid NOT NULL REFERENCES sf_knowledge_snapshot(id),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  delivered_at timestamptz
);
