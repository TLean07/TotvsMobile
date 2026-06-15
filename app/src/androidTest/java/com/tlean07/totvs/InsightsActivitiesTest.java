package com.tlean07.totvs;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.tlean07.totvs.ui.insights.OportunidadesActivity;
import com.tlean07.totvs.ui.insights.RiscosActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InsightsActivitiesTest {

    @Test
    public void riscosScreenShowsExpectedFilters() {
        try (ActivityScenario<RiscosActivity> ignored = ActivityScenario.launch(RiscosActivity.class)) {
            onView(withText("Riscos")).check(matches(isDisplayed()));
            onView(withText("Todos os tipos")).check(matches(isDisplayed()));
            onView(withText("Todas severidades")).check(matches(isDisplayed()));
        }
    }

    @Test
    public void oportunidadesScreenShowsExpectedFilters() {
        try (ActivityScenario<OportunidadesActivity> ignored =
                     ActivityScenario.launch(OportunidadesActivity.class)) {
            onView(withText("Oportunidades")).check(matches(isDisplayed()));
            onView(withText("Todas")).check(matches(isDisplayed()));
            onView(withText("Novo produto")).check(matches(isDisplayed()));
        }
    }
}
