package com.mytallybook.accountbook.entry;

import com.mytallybook.accountbook.common.api.ApiResponse;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import com.mytallybook.accountbook.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/entries")
public class EntryController {
    private final EntryService service;
    public EntryController(EntryService service) { this.service = service; }

    @GetMapping
    public ApiResponse<EntryModels.EntryPage> list(
            @AuthenticationPrincipal CurrentUser actor,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize,
            HttpServletRequest request) {
        var filters = EntryFilters.parse(dateFrom, dateTo, entryType, categoryId, accountId,
                createdBy, keyword, page, pageSize);
        return ApiResponse.success(service.list(actor, filters), RequestIdFilter.getRequestId(request));
    }

    @GetMapping("/creators")
    public ApiResponse<EntryModels.CreatorList> creators(
            @AuthenticationPrincipal CurrentUser actor, HttpServletRequest request) {
        return ApiResponse.success(service.creators(actor), RequestIdFilter.getRequestId(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<EntryModels.EntryView> get(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success(service.get(actor, id), RequestIdFilter.getRequestId(request));
    }

    @PostMapping
    public ApiResponse<EntryModels.EntryView> create(
            @AuthenticationPrincipal CurrentUser actor, @RequestBody EntryModels.CreateRequest body,
            HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(service.create(actor, body.value(), requestId), requestId);
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public ApiResponse<EntryModels.EntryView> update(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable long id,
            @RequestBody EntryModels.UpdateRequest body, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        return ApiResponse.success(service.update(actor, id, body.value(), requestId), requestId);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal CurrentUser actor, @PathVariable long id,
            @RequestParam Long version, HttpServletRequest request) {
        String requestId = RequestIdFilter.getRequestId(request);
        service.delete(actor, id, version, requestId);
        return ApiResponse.success(null, requestId);
    }
}
