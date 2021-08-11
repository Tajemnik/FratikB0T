/*
 * Copyright (C) 2019-2021 FratikB0T Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.fratik.moderation.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import pl.fratik.moderation.entity.Case;
import pl.fratik.moderation.entity.CaseDao;
import pl.fratik.moderation.entity.OldCase;
import pl.fratik.moderation.entity.OldCaseRow;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public enum Migration {
    V0(null) {
        @Override
        public void migrate(Connection con) throws SQLException, IOException {
            ObjectMapper objectMapper = new ObjectMapper();
            List<Case> cases = new ArrayList<>();
            Map<String, String> masterMap = new HashMap<>();
            try (PreparedStatement stmt = con.prepareStatement("SELECT * FROM cases;")) {
                ResultSet set = stmt.executeQuery();
                if (!set.isBeforeFirst()) return;
                while (set.next()) {
                    List<Case> newCases = new ArrayList<>();
                    long caseId = 0;
                    long guildId = Long.parseUnsignedLong(set.getString("id"));
                    for (OldCase data : objectMapper.readValue(set.getString("data"), OldCaseRow.class).getCases()) {
                        EnumSet<Case.Flaga> flagi = EnumSet.noneOf(Case.Flaga.class);
                        flagi.addAll(data.getFlagi().stream().map(f -> Case.Flaga.valueOf(f.name())).collect(Collectors.toSet()));
                        String reason = data.getReason();
                        if (reason != null && reason.startsWith("translate:")) reason = "\\" + reason;
                        Case c = new Case(CaseDao.getId(guildId, data.getCaseId()), guildId,
                                Long.parseUnsignedLong(data.getUserId()), caseId = data.getCaseId(),
                                Objects.requireNonNull(data.getTimestamp()), data.getType(), data.isValid(),
                                data.getValidTo(),
                                data.getMessageId() == null ? null : Long.parseUnsignedLong(data.getMessageId()),
                                data.getDmMsgId() == null ? null : Long.parseUnsignedLong(data.getDmMsgId()),
                                data.getIssuerId() == null ? null : Long.parseUnsignedLong(data.getIssuerId()),
                                reason, data.getIleRazy() == null ? 1 : data.getIleRazy(), flagi, data.getDowody(), false);
                        newCases.add(c);
                    }
                    cases.addAll(newCases);
                    masterMap.put(Long.toUnsignedString(guildId), Long.toString(caseId));
                }
            }
            try (PreparedStatement stmt = con.prepareStatement("DELETE FROM cases;")) {
                stmt.execute();
            }
            for (Map.Entry<String, String> e : masterMap.entrySet()) {
                try (PreparedStatement stmt = con.prepareStatement("INSERT INTO cases (id, data) VALUES (?, jsonb_object('caseId', ?));")) {
                    stmt.setString(1, e.getKey());
                    stmt.setString(2, e.getValue());
                    stmt.execute();
                }
            }
            for (Case c : cases) {
                try (PreparedStatement stmt = con.prepareStatement("INSERT INTO cases (id, data) VALUES (?, to_jsonb(?::jsonb));")) {
                    stmt.setString(1, c.getId());
                    stmt.setString(2, objectMapper.writeValueAsString(c));
                    stmt.execute();
                }
            }
        }
    },
    V1("1") {
        @Override
        public void migrate(Connection con) {
            // najnowsza
        }
    };

    public static Migration getNewest() {
        return V1;
    }

    @Getter private final String versionKey;

    Migration(String versionKey) {
        this.versionKey = versionKey;
    }

    public static Migration fromVersionName(String preMigrationVersion) {
        for (Migration v : values()) {
            if (Objects.equals(v.versionKey, preMigrationVersion)) return v;
        }
        return null;
    }

    public abstract void migrate(Connection con) throws SQLException, IOException;
}
