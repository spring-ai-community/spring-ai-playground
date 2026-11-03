/*
 * Copyright © 2025 Jemin Huh (hjm1980@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springaicommunity.playground.webui.observability;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.playground.observability.pricing.CurrencyService;
import org.springaicommunity.playground.observability.pricing.ModelPricingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ModelPricingCurrencyDialogTest extends SpringBrowserlessTest {

    @Autowired
    private ModelPricingService pricingService;

    @Autowired
    private CurrencyService currencyService;

    @AfterEach
    void restoreCurrency() {
        this.currencyService.setActiveCurrency("USD");
    }

    @Test
    void addingACustomCurrencyWithoutAnIdShowsTheServiceMessage() {
        ModelPricingManagerDialog dialog = openDialog();

        test(addCustomButton(dialog)).click();
        roundTrip();

        assertThat($(Notification.class).all())
                .extracting(notification -> notification.getElement().getProperty("text"))
                .contains("ID is required (1-8 characters)");
        assertThat(this.currencyService.getActiveCurrency()).isEqualTo("USD");
    }

    @Test
    void addingACustomCurrencyActivatesItThroughTheService() {
        ModelPricingManagerDialog dialog = openDialog();
        test(textField(dialog, "e.g. AUD or MYCUR")).setValue("BLT");
        test(textField(dialog, "e.g. A$")).setValue("B$");
        test($(BigDecimalField.class, dialog)
                .withCondition(field -> "e.g. 1.52".equals(field.getPlaceholder())).single())
                .setValue(new BigDecimal("3"));

        test(addCustomButton(dialog)).click();
        roundTrip();

        assertThat(this.currencyService.getActiveCurrency()).isEqualTo("BLT");
        assertThat(this.currencyService.getActiveRate().symbol()).isEqualTo("B$");
        assertThat(textField(dialog, "e.g. AUD or MYCUR").getValue()).isEmpty();
    }

    private ModelPricingManagerDialog openDialog() {
        ModelPricingManagerDialog dialog = new ModelPricingManagerDialog(this.pricingService, this.currencyService);
        dialog.open();
        roundTrip();
        return dialog;
    }

    private TextField textField(ModelPricingManagerDialog dialog, String placeholder) {
        return $(TextField.class, dialog)
                .withCondition(field -> placeholder.equals(field.getPlaceholder())).single();
    }

    private Button addCustomButton(ModelPricingManagerDialog dialog) {
        return $(Button.class, dialog).withCondition(button -> "Add custom".equals(button.getText())).single();
    }

}
