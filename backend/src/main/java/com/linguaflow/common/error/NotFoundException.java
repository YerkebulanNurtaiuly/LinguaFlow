package com.linguaflow.common.error;

/** Also used for resources owned by another user, so their existence is not leaked. */
public class NotFoundException extends ApiException {

	public NotFoundException(String detail) {
		super(ErrorCode.NOT_FOUND, detail);
	}

	public static NotFoundException of(String resource, Object id) {
		return new NotFoundException(resource + " " + id + " not found");
	}

}
