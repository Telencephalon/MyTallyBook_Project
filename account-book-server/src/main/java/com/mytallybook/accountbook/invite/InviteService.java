package com.mytallybook.accountbook.invite;
import com.mytallybook.accountbook.auth.service.AuthResult;
import com.mytallybook.accountbook.auth.session.SessionTokenService;
import com.mytallybook.accountbook.auth.wechat.WechatSessionClient;
import com.mytallybook.accountbook.common.error.*;
import com.mytallybook.accountbook.security.CurrentUser;
import org.springframework.stereotype.Service;
@Service
public class InviteService {
    private final InviteTokenService tokens;
    private final SessionTokenService sessions;
    private final WechatSessionClient wechat;
    private final InviteTransactionService transactions;
    public InviteService(InviteTokenService tokens, SessionTokenService sessions,
                         WechatSessionClient wechat, InviteTransactionService transactions) {
        this.tokens=tokens; this.sessions=sessions; this.wechat=wechat; this.transactions=transactions;
    }
    public CreatedInvite create(CurrentUser actor, Integer expiresInHours, String requestId) {
        int hours=expiresInHours == null ? 24 : expiresInHours;
        if(hours < 1 || hours > 168) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String raw=tokens.generate();
        return transactions.create(actor, hours, raw, tokens.hash(raw), requestId);
    }
    public InvitePage list(CurrentUser actor, int page, int pageSize, String status) {
        if(page < 1 || pageSize < 1 || pageSize > 50
                || (status != null && !InviteStatus.VALUES.contains(status))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        int offset;
        try { offset=Math.multiplyExact(page-1, pageSize); }
        catch(ArithmeticException exception) { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
        return transactions.list(actor,page,pageSize,offset,status);
    }
    public RevokedInvite revoke(CurrentUser actor, long inviteId, String requestId) {
        requireSafeId(inviteId);
        return transactions.revoke(actor,inviteId,requestId);
    }
    public AuthResult accept(String code, String inviteToken, String requestId) {
        if(code == null || code.isBlank() || code.length()>256) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String hash=tokens.hash(inviteToken);
        var identity=wechat.exchange(code.trim());
        var session=sessions.issue();
        return transactions.accept(identity,hash,session,requestId);
    }
    public static long requireSafeId(long id) {
        if(id < 1 || id > 9007199254740991L) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return id;
    }
}
