ALTER TABLE category DROP CHECK ck_category_type;
ALTER TABLE category ADD CONSTRAINT ck_category_type CHECK (entry_type IN ('INCOME','EXPENSE','BOTH'));
