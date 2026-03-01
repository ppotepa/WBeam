package com.wbeam.compat;

import org.json.JSONObject;

public interface ApiCompatPolicy {
    String policyName();
    int minSdk();
    int maxSdk();
    JSONObject apply(JSONObject base);
}
