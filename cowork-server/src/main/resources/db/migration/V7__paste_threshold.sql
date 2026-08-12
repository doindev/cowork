-- Pasted chat text longer than this many characters is collapsed into a removable
-- placeholder chip in the input (still sent inline with the message); 0 disables.
ALTER TABLE conversation ADD COLUMN paste_threshold int NOT NULL DEFAULT 250;
