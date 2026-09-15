package com.tlean07.totvs;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.not;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.tlean07.totvs.ui.gravador.GravacoesActivity;
import com.tlean07.totvs.ui.gravador.GravadorActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Smoke tests for the recorder screens (no microphone or network needed). */
@RunWith(AndroidJUnit4.class)
public class RecorderScreensTest {

    @Test
    public void recorderStartsIdleWithStopDisabled() {
        try (ActivityScenario<GravadorActivity> ignored = ActivityScenario.launch(GravadorActivity.class)) {
            onView(withText("Gravar reunião")).check(matches(isDisplayed()));
            onView(withId(R.id.gravClock)).check(matches(withText("00:00")));
            onView(withId(R.id.gravMain)).check(matches(isEnabled()));
            onView(withId(R.id.gravStop)).check(matches(not(isEnabled())));
            onView(withId(R.id.gravMarker)).check(matches(not(isEnabled())));
        }
    }

    @Test
    public void recordingsScreenShowsActions() {
        try (ActivityScenario<GravacoesActivity> ignored = ActivityScenario.launch(GravacoesActivity.class)) {
            onView(withText("Minhas gravações")).check(matches(isDisplayed()));
            onView(withId(R.id.lstRecord)).check(matches(isDisplayed()));
            onView(withId(R.id.lstImport)).check(matches(isDisplayed()));
            onView(withId(R.id.lstSettings)).check(matches(isDisplayed()));
        }
    }
}
