package com.example.retention.obligations;

import java.util.LinkedHashMap;
import java.util.Map;

/** 求值代价计量：路线A记录遍历范围，路线B记录账载读取与补偿应用。 */
public class EvalStats {

    public int eventsFolded;
    public int segmentsVisited;
    public int linksWalked;
    public int factsLoaded;
    public int rulesConsidered;
    public int ledgerEntriesRead;
    public int voidCompensationsApplied;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventsFolded", eventsFolded);
        m.put("segmentsVisited", segmentsVisited);
        m.put("linksWalked", linksWalked);
        m.put("factsLoaded", factsLoaded);
        m.put("rulesConsidered", rulesConsidered);
        m.put("ledgerEntriesRead", ledgerEntriesRead);
        m.put("voidCompensationsApplied", voidCompensationsApplied);
        return m;
    }
}
