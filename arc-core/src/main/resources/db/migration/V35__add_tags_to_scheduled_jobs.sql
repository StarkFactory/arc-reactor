-- Add tags column to scheduled_jobs (comma-separated string)
ALTER TABLE scheduled_jobs ADD COLUMN tags VARCHAR(1000);
