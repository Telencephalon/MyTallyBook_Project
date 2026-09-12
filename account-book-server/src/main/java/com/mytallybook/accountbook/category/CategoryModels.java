package com.mytallybook.accountbook.category;

import com.mytallybook.accountbook.common.validation.StrictRequest;
import tools.jackson.databind.JsonNode;

import java.util.List;

public final class CategoryModels {
    private CategoryModels() {}

    public record CategoryView(long id, String entryType, String name, String icon, String color,
                               int sortNo, boolean systemDefault, String status) {}
    public record CategoryList(List<CategoryView> items) {}
    public record CategoryInput(String entryType, String name, String icon, String color,
                                Integer sortNo, String status) {}
    public record CategoryUpdate(String entryType, String name, String icon, String color, Integer sortNo, String status) {}

    public static final class CreateRequest extends StrictRequest {
        private String entryType;
        private String name;
        private String icon;
        private String color;
        private String status;
        private Integer sortNo;

        public void setEntryType(JsonNode value) { entryType = string(value); }
        public void setName(JsonNode value) { name = string(value); }
        public void setIcon(JsonNode value) { icon = nullableString(value); }
        public void setColor(JsonNode value) { color = nullableString(value); }
        public void setSortNo(JsonNode value) { sortNo = integer(value); }
        public void setStatus(JsonNode value) { status = string(value); }

        public CategoryInput value() {
            return new CategoryInput(entryType, name, icon, color, sortNo, status);
        }
    }

    public static final class UpdateRequest extends StrictRequest {
        private String entryType;
        private String name;
        private String icon;
        private String color;
        private String status;
        private Integer sortNo;

        public void setEntryType(JsonNode value) { entryType = string(value); }
        public void setName(JsonNode value) { name = string(value); }
        public void setIcon(JsonNode value) { icon = nullableString(value); }
        public void setColor(JsonNode value) { color = nullableString(value); }
        public void setSortNo(JsonNode value) { sortNo = integer(value); }
        public void setStatus(JsonNode value) { status = string(value); }

        public CategoryUpdate value() {
            return new CategoryUpdate(entryType, name, icon, color, sortNo, status);
        }
    }
}
