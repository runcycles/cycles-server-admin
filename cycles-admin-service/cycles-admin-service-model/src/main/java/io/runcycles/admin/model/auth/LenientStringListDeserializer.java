package io.runcycles.admin.model.auth;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deserializes a JSON value into {@code List<String>}, tolerating the historical
 * corruption where Redis cjson's empty-array round-trip turned {@code []} into
 * {@code {}} (empty object). An empty object token stream is accepted and
 * mapped to an empty list; any non-empty object is rejected as before.
 * <p>
 * This lets admin services read legacy {@link ApiKey} records that were written
 * by the old Lua revoke path (pre v0.1.25.17) without silently dropping them.
 * New writes always go through Jackson and produce proper {@code []}.
 */
public class LenientStringListDeserializer extends JsonDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.START_OBJECT) {
            // Accept only an empty object — this is the cjson corruption case.
            // Any content makes it ambiguous; let Jackson raise the mismatch.
            JsonToken next = p.nextToken();
            if (next == JsonToken.END_OBJECT) {
                return new ArrayList<>();
            }
            return (List<String>) ctxt.handleUnexpectedToken(List.class, p);
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.START_ARRAY) {
            List<String> out = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                out.add(p.getValueAsString());
            }
            return out;
        }
        return Collections.emptyList();
    }
}
