ALTER TABLE book_entry
    ADD COLUMN person_name VARCHAR(64) NULL COMMENT '人情往来人名' AFTER note;
