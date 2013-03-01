package edu.ucsb.cs.mdcc.dao;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class InMemoryDatabase implements Database {

    private Map<String,Record> db = new HashMap<String, Record>();

    public Record get(String key) {
        Record record = db.get(key);
        if (record == null) {
            record = new Record(key);
        }
        return record;
    }

    public Collection<Record> getAll() {
        return db.values();
    }

    public void put(Record record) {
        db.put(record.getKey(), record);
    }
}
