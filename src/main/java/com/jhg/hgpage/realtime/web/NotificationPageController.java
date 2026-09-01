package com.jhg.hgpage.realtime.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class NotificationPageController {

    @GetMapping("/notifications")
    public String notifications() {
        return "notifications";
    }
}
