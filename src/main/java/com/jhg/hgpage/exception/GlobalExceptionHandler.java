package com.jhg.hgpage.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
