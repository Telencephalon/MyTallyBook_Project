package com.mytallybook.accountbook.statistics;

import com.mytallybook.accountbook.common.api.ApiResponse;
import com.mytallybook.accountbook.common.web.RequestIdFilter;
import com.mytallybook.accountbook.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/statistics")
public class StatisticsController {
    private final StatisticsService service;

    public StatisticsController(StatisticsService service) {
        this.service = service;
    }

    @GetMapping("/monthly-summary")
    public ApiResponse<StatisticsModels.MonthlySummary> summary(
            @AuthenticationPrincipal CurrentUser actor, @RequestParam(required = false) String month,
            HttpServletRequest request) {
        return ApiResponse.success(service.summary(actor, month), RequestIdFilter.getRequestId(request));
    }

    @GetMapping("/daily-trend")
    public ApiResponse<StatisticsModels.DailyTrend> daily(
            @AuthenticationPrincipal CurrentUser actor, @RequestParam(required = false) String month,
            HttpServletRequest request) {
        return ApiResponse.success(service.daily(actor, month), RequestIdFilter.getRequestId(request));
    }

    @GetMapping("/categories")
    public ApiResponse<StatisticsModels.Ranking> categories(
            @AuthenticationPrincipal CurrentUser actor, @RequestParam(required = false) String month,
            @RequestParam(required = false) String entryType, HttpServletRequest request) {
        return ApiResponse.success(service.categories(actor, month, entryType), RequestIdFilter.getRequestId(request));
    }

    @GetMapping("/accounts")
    public ApiResponse<StatisticsModels.AccountStatistics> accounts(
            @AuthenticationPrincipal CurrentUser actor, @RequestParam(required = false) String month,
            HttpServletRequest request) {
        return ApiResponse.success(service.accounts(actor, month), RequestIdFilter.getRequestId(request));
    }

    @GetMapping("/members")
    public ApiResponse<StatisticsModels.Ranking> members(
            @AuthenticationPrincipal CurrentUser actor, @RequestParam(required = false) String month,
            @RequestParam(required = false) String entryType, HttpServletRequest request) {
        return ApiResponse.success(service.members(actor, month, entryType), RequestIdFilter.getRequestId(request));
    }
}
