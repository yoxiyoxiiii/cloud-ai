package com.cloudai.sso.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class RefreshRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String refreshToken;
}
