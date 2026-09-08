package com.mytallybook.accountbook.user;

import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize
public final class UpdateProfileRequest {

    private boolean nicknamePresent;
    private String nickname;
    private boolean avatarUrlPresent;
    private String avatarUrl;

    public void setNickname(String nickname) {
        this.nicknamePresent = true;
        this.nickname = nickname;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrlPresent = true;
        this.avatarUrl = avatarUrl;
    }

    public boolean nicknamePresent() {
        return nicknamePresent;
    }

    public String nickname() {
        return nickname;
    }

    public boolean avatarUrlPresent() {
        return avatarUrlPresent;
    }

    public String avatarUrl() {
        return avatarUrl;
    }
}
