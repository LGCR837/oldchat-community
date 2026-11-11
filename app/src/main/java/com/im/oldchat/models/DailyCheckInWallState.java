package com.im.oldchat.models;

import java.util.ArrayList;
import java.util.List;

public class DailyCheckInWallState {
    public int checkInCount;
    public boolean checkedIn;
    public boolean alreadyPosted;
    public DailyCheckInWallMessage myPost;
    public List<DailyCheckInWallMessage> featuredMessages = new ArrayList<DailyCheckInWallMessage>();
}
