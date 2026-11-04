package com.restaurant.pos.auth.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public robots.txt controller for API/Application subdomains.
 * Returns HTTP 200 OK with Disallow: / to prevent search engines from
 * indexing transactional backend endpoints while clearing Google Search Console 401 crawl errors.
 */
@RestController
public class RobotsController {

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getRobotsTxt() {
        return "User-agent: *\nDisallow: /\n";
    }
}
