package com.jhg.hgpage.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;

/**
 * 전역 예외 처리.
 * /api/** 요청에는 ProblemDetail JSON, 화면 요청에는 error.html 또는 flash 리다이렉트로 응답한다.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public Object handleEntityNotFound(EntityNotFoundException e, HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Object handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(NotEnoughStockException.class)
    public Object handleNotEnoughStock(NotEnoughStockException e, HttpServletRequest request) {
        if (isApiRequest(request)) {
            return problem(HttpStatus.CONFLICT, e.getMessage());
        }

        RequestContextUtils.getOutputFlashMap(request)
                .put("errorMessage", "재고가 부족하여 주문을 완료하지 못했습니다. 수량을 확인해 주세요.");
        return new ModelAndView("redirect:/main");
    }

    // 낙관적 락 충돌(동시 재고 수정 등): 사용자 재시도로 해소되는 일시적 충돌이므로 재시도를 안내한다.
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public Object handleOptimisticLockingFailure(OptimisticLockingFailureException e, HttpServletRequest request) {
        if (isApiRequest(request)) {
            return problem(HttpStatus.CONFLICT, "Concurrent modification detected. Please retry.");
        }

        RequestContextUtils.getOutputFlashMap(request)
                .put("errorMessage", "주문이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        return new ModelAndView("redirect:/main");
    }

    // WMS 통신 실패(연결 거부·타임아웃): 조회 어댑터는 자체 폴백(빈 맵/빈 목록)하므로
    // 여기 도달하는 것은 쓰기 경로(ship/release/adjust/발주)다. 트랜잭션은 롤백돼 있다.
    @ExceptionHandler(ResourceAccessException.class)
    public Object handleResourceAccess(ResourceAccessException e, HttpServletRequest request) {
        if (isApiRequest(request)) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "창고 시스템과 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }

        RequestContextUtils.getOutputFlashMap(request)
                .put("errorMessage", "창고 시스템과 통신하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        return new ModelAndView("redirect:/main");
    }

    private Object errorResponse(HttpStatus status, String message, HttpServletRequest request) {
        if (isApiRequest(request)) {
            return problem(status, message);
        }

        ModelAndView mav = new ModelAndView("error");
        mav.setStatus(status);
        mav.addObject("status", status.value());
        mav.addObject("error", status.getReasonPhrase());
        mav.addObject("message", message);
        return mav;
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status)
                .body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    private boolean isApiRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/");
    }
}
