package com.mytallybook.accountbook.entry;

import com.mytallybook.accountbook.common.validation.StrictRequest;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class EntryModels {
    private EntryModels() {}

    public record EntryView(long id, String entryType, String amount,
                            long categoryId, String categoryName, String categoryStatus,
                            long accountId, String accountName, String accountStatus,
                            String entryDate, String note, long createdBy, String creatorName,
                            Instant createdAt, Instant updatedAt, long version,
                            boolean canEdit, boolean canDelete, String clientRequestId) {}
    public record EntryPage(List<EntryView> items, int page, int pageSize, long total) {}
    public record CreatorOption(long userId, String displayName) {}
    public record CreatorList(List<CreatorOption> items) {}
    public record EntryInput(String entryType, String amount, long categoryId, long accountId,
                             String entryDate, String note, String clientRequestId) {}
    public record EntryUpdate(String entryType, String amount, long categoryId, long accountId,
                              String entryDate, String note, Long version) {}

    public static final class CreateRequest extends StrictRequest {
        private String entryType;
        private String amount;
        private Long categoryId;
        private Long accountId;
        private String entryDate;
        private String note;
        private String clientRequestId;

        public void setEntryType(JsonNode value) { entryType = string(value); }
        public void setAmount(JsonNode value) { amount = string(value); }
        public void setCategoryId(JsonNode value) { categoryId = longInteger(value); }
        public void setAccountId(JsonNode value) { accountId = longInteger(value); }
        public void setEntryDate(JsonNode value) { entryDate = string(value); }
        public void setNote(JsonNode value) { note = nullableString(value); }
        public void setClientRequestId(JsonNode value) { clientRequestId = string(value); }

        public EntryInput value() {
            return new EntryInput(entryType, amount, required(categoryId), required(accountId),
                    entryDate, note, clientRequestId);
        }
    }

    public static final class UpdateRequest extends StrictRequest {
        private String entryType;
        private String amount;
        private Long categoryId;
        private Long accountId;
        private String entryDate;
        private String note;
        private Long version;

        public void setEntryType(JsonNode value) { entryType = string(value); }
        public void setAmount(JsonNode value) { amount = string(value); }
        public void setCategoryId(JsonNode value) { categoryId = longInteger(value); }
        public void setAccountId(JsonNode value) { accountId = longInteger(value); }
        public void setEntryDate(JsonNode value) { entryDate = string(value); }
        public void setNote(JsonNode value) { note = nullableString(value); }
        public void setVersion(JsonNode value) { version = longInteger(value); }

        public EntryUpdate value() {
            return new EntryUpdate(entryType, amount, required(categoryId), required(accountId),
                    entryDate, note, version);
        }
    }

    private static long required(Long value) {
        if (value == null) throw new com.mytallybook.accountbook.common.error.BusinessException(
                com.mytallybook.accountbook.common.error.ErrorCode.VALIDATION_FAILED);
        return value;
    }
}
