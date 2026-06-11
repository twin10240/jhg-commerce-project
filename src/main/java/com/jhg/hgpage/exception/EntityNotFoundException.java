package com.jhg.hgpage.exception;

public class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String target, Long id) {
        super(target + " not found: id=" + id);
    }
}
