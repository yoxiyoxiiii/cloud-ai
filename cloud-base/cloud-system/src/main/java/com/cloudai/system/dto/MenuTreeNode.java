package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class MenuTreeNode implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long parentId;
    private String name;
    private String perms;
    private String type;
    private Integer sort;
    private List<MenuTreeNode> children = new ArrayList<>();
}
