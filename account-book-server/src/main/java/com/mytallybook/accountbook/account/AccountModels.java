package com.mytallybook.accountbook.account;

import com.mytallybook.accountbook.common.validation.StrictRequest;
import tools.jackson.databind.JsonNode;

import java.util.List;

public final class AccountModels {
    private AccountModels() {}

    public record AccountView(long id, String name, String accountType, String initialBalance,
                              String currentBalance, int sortNo, String status, long version) {}
    public record AccountList(List<AccountView> items) {}
    public record AccountInput(String name, String accountType, String initialBalance,
                               Integer sortNo, String status) {}
    public record AccountUpdate(String name, Integer sortNo, String status, Long version) {}

    public static final class CreateRequest extends StrictRequest {
        private String name;
        private String accountType;
        private String initialBalance;
        private String status;
        private Integer sortNo;

        public void setName(JsonNode value) { name = string(value); }
        public void setAccountType(JsonNode value) { accountType = string(value); }
        public void setInitialBalance(JsonNode value) { initialBalance = string(value); }
        public void setSortNo(JsonNode value) { sortNo = integer(value); }
        public void setStatus(JsonNode value) { status = string(value); }

        public AccountInput value() {
            return new AccountInput(name, accountType, initialBalance, sortNo, status);
        }
    }

    public static final class UpdateRequest extends StrictRequest {
        private String name;
        private String status;
        private Integer sortNo;
        private Long version;

        public void setName(JsonNode value) { name = string(value); }
        public void setSortNo(JsonNode value) { sortNo = integer(value); }
        public void setStatus(JsonNode value) { status = string(value); }
        public void setVersion(JsonNode value) { version = longInteger(value); }

        public AccountUpdate value() {
            return new AccountUpdate(name, sortNo, status, version);
        }
    }
}
