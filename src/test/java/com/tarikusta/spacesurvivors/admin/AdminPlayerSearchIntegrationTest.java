package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.support.DatabaseTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Searching and paging the player list.
 *
 * <p>Every search here uses a prefix no other test writes. Other test classes commit players
 * (the device-token tests are not transactional), so an unfiltered count would depend on which
 * classes happened to run first.</p>
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminPlayerSearchIntegrationTest {

    @Autowired
    private AdminPlayerSearch search;

    @Autowired
    private JdbcClient db;

    @Autowired
    private MockMvc mvc;

    private UUID player(String name, String country, boolean test) {
        return db.sql("""
                        INSERT INTO player_profile (device_id, display_name, country, device_secret_hash, created_by_admin)
                        VALUES (:d, :n, :c, 'x', :t) RETURNING player_id""")
                .param("d", "search-" + UUID.randomUUID()).param("n", name)
                .param("c", country).param("t", test)
                .query(UUID.class).single();
    }

    @Test
    @DisplayName("a name fragment matches regardless of case")
    void matchesAFragment() {
        player("Srch_Alpha", "TR", false);
        player("srch_alphaTwo", "US", false);
        player("Srch_Beta", "US", false);

        assertThat(search.search("SRCH_ALPHA", AdminPlayerSearch.Filter.ALL, 0).matching()).isEqualTo(2);
    }

    @Test
    @DisplayName("an underscore in the search is a character, not LIKE's wildcard")
    void underscoreIsLiteral() {
        player("Qz_Lit", null, false);
        player("QzxLit", null, false);

        var page = search.search("Qz_Lit", AdminPlayerSearch.Filter.ALL, 0);
        assertThat(page.rows()).extracting(AdminPlayerRow::displayName).containsExactly("Qz_Lit");
    }

    @Test
    @DisplayName("a whole player id finds exactly that player")
    void findsById() {
        UUID id = player("Srch_ById", null, false);
        player("Srch_Other", null, false);

        var page = search.search(id.toString(), AdminPlayerSearch.Filter.ALL, 0);
        assertThat(page.rows()).extracting(AdminPlayerRow::playerId).containsExactly(id);
    }

    @Test
    @DisplayName("filters narrow the search: test, real, with and without a save")
    void filters() {
        UUID saved = player("Flt_Real", null, false);
        player("Flt_Test", null, true);
        db.sql("INSERT INTO player_progress (player_id, progress_data) VALUES (:p, '{}'::jsonb)")
                .param("p", saved).update();

        assertThat(search.search("Flt_", AdminPlayerSearch.Filter.TEST, 0).rows())
                .extracting(AdminPlayerRow::displayName).containsExactly("Flt_Test");
        assertThat(search.search("Flt_", AdminPlayerSearch.Filter.REAL, 0).rows())
                .extracting(AdminPlayerRow::displayName).containsExactly("Flt_Real");
        assertThat(search.search("Flt_", AdminPlayerSearch.Filter.SAVED, 0).rows())
                .extracting(AdminPlayerRow::displayName).containsExactly("Flt_Real");
        assertThat(search.search("Flt_", AdminPlayerSearch.Filter.UNSAVED, 0).rows())
                .extracting(AdminPlayerRow::displayName).containsExactly("Flt_Test");
    }

    @Test
    @DisplayName("results come in pages of fifty, and the page says where it is")
    void pages() throws Exception {
        for (int i = 0; i < 55; i++) {
            player("Pgx_" + i, null, false);
        }

        var first = search.search("Pgx_", AdminPlayerSearch.Filter.ALL, 0);
        assertThat(first.rows()).hasSize(50);
        assertThat(first.matching()).isEqualTo(55);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.hasPrevious()).isFalse();

        var second = search.search("Pgx_", AdminPlayerSearch.Filter.ALL, 1);
        assertThat(second.rows()).hasSize(5);
        assertThat(second.hasNext()).isFalse();
        assertThat(second.from()).isEqualTo(51);
        assertThat(second.to()).isEqualTo(55);

        mvc.perform(get("/admin/players").param("q", "Pgx_").param("page", "1")
                        .with(user("testadmin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("of <span>55</span>")))
                .andExpect(content().string(containsString("Previous")));
    }

    @Test
    @DisplayName("nonsense in the page or filter parameter does not break the page")
    void toleratesJunkParameters() throws Exception {
        mvc.perform(get("/admin/players").param("filter", "'; drop table").param("page", "-3")
                        .with(user("testadmin").roles("ADMIN")))
                .andExpect(status().isOk());
    }
}
