package com.ai.agent.api;

import org.junit.jupiter.api.Test;

import static com.ai.agent.api.ConfirmationIntentService.ConfirmationIntent.AMBIGUOUS;
import static com.ai.agent.api.ConfirmationIntentService.ConfirmationIntent.CONFIRM;
import static com.ai.agent.api.ConfirmationIntentService.ConfirmationIntent.REJECT;
import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationIntentServiceTest {
    private final ConfirmationIntentService service = new ConfirmationIntentService();

    @Test
    void noProblemCancelItIsConfirmationInsteadOfRejection() {
        assertThat(service.classify("no problem, cancel it")).isEqualTo(CONFIRM);
        assertThat(service.classify("go ahead")).isEqualTo(CONFIRM);
        assertThat(service.classify("确认取消")).isEqualTo(CONFIRM);
        assertThat(service.classify("确认取消这个订单")).isEqualTo(CONFIRM);
        assertThat(service.classify("请确认取消这笔订单")).isEqualTo(CONFIRM);
    }

    @Test
    void bareNoIsRejection() {
        assertThat(service.classify("no")).isEqualTo(REJECT);
    }

    @Test
    void negativePhrasesContainingConfirmWordsAreRejections() {
        assertThat(service.classify("no problem, don't cancel")).isEqualTo(REJECT);
        assertThat(service.classify("不可以执行")).isEqualTo(REJECT);
        assertThat(service.classify("不要执行")).isEqualTo(REJECT);
        assertThat(service.classify("do not go ahead")).isEqualTo(REJECT);
        assertThat(service.classify("please don't go ahead")).isEqualTo(REJECT);
        assertThat(service.classify("don’t cancel it")).isEqualTo(REJECT);
        assertThat(service.classify("please don’t go ahead")).isEqualTo(REJECT);
        assertThat(service.classify("别继续")).isEqualTo(REJECT);
        assertThat(service.classify("不要继续取消")).isEqualTo(REJECT);
        assertThat(service.classify("别确认取消")).isEqualTo(REJECT);
        assertThat(service.classify("不要取消吧")).isEqualTo(REJECT);
        assertThat(service.classify("不要取消")).isEqualTo(REJECT);
        assertThat(service.classify("please don't proceed")).isEqualTo(REJECT);
        assertThat(service.classify("do not confirm")).isEqualTo(REJECT);
        assertThat(service.classify("别取消吧")).isEqualTo(REJECT);
    }

    @Test
    void questionsAboutConfirmationActionsAreAmbiguous() {
        assertThat(service.classify("what happens if I cancel it?")).isEqualTo(AMBIGUOUS);
        assertThat(service.classify("do I need to cancel it?")).isEqualTo(AMBIGUOUS);
        assertThat(service.classify("这个可以取消吗")).isEqualTo(AMBIGUOUS);
    }

    @Test
    void chineseRejectAndConfirmAreSeparated() {
        assertThat(service.classify("算了，不取消了")).isEqualTo(REJECT);
        assertThat(service.classify("确认取消")).isEqualTo(CONFIRM);
    }

    @Test
    void unrelatedTextIsAmbiguous() {
        assertThat(service.classify("我再想一下")).isEqualTo(AMBIGUOUS);
        assertThat(service.classify("no problem")).isEqualTo(AMBIGUOUS);
        assertThat(service.classify("可以再等等吗")).isEqualTo(AMBIGUOUS);
    }
}
