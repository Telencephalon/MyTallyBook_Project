package com.mytallybook.accountbook.auth.web;

public record LogoutResponse(String state) {

    public static LogoutResponse loggedOut() {
        return new LogoutResponse("LOGGED_OUT");
    }
}
