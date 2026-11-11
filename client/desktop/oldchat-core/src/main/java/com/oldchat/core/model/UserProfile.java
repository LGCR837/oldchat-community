package com.oldchat.core.model;

import java.util.ArrayList;
import java.util.List;

/** 用户空间资料（含动态列表）。 */
public class UserProfile {
    public User user;
    public int friendsCount;
    public List<Moment> moments = new ArrayList<>();
}
