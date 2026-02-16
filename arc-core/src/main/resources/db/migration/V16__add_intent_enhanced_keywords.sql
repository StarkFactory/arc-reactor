ALTER TABLE intent_definitions ADD COLUMN synonyms TEXT NOT NULL DEFAULT '{}';
ALTER TABLE intent_definitions ADD COLUMN keyword_weights TEXT NOT NULL DEFAULT '{}';
ALTER TABLE intent_definitions ADD COLUMN negative_keywords TEXT NOT NULL DEFAULT '[]';
