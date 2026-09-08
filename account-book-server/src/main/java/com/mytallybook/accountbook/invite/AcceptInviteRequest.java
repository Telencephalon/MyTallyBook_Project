package com.mytallybook.accountbook.invite;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record AcceptInviteRequest(@NotBlank @Size(max=256) String code,
                                  @NotBlank @Size(max=256) String inviteToken) {}
