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
package org.springaicommunity.playground.webui.home;

import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.springaicommunity.playground.webui.SpringAiPlaygroundAppLayout;
import org.springaicommunity.playground.webui.VaadinUtils;
import org.springaicommunity.playground.webui.common.ContentWorkspaceView;
import org.springaicommunity.playground.webui.home.HomeItemView.HomeItem;

import java.util.Objects;

@PageTitle("Home")
@Route(value = "", layout = SpringAiPlaygroundAppLayout.class)
public class HomeView extends ContentWorkspaceView {

    private final HomeItemView homeItemView;
    private HomeContentView homeContentView;

    public HomeView() {
        this.homeItemView = new HomeItemView(this::selectHomeItem);
        this.homeContentView = new HomeContentView();

        configureSidebar(this.homeItemView, "Home Contents");

        selectHomeItem(homeItemView.buildDefaultHomeItem());
    }

    private void selectHomeItem(HomeItem homeItem) {
        if (Objects.isNull(homeItem))
            return;

        if (homeItem.actionType().equals(HomeItemView.ActionType.EXTERNAL_URL)) {
            this.homeContentView.setContent(homeItem);
            return;
        }

        this.homeContentView = new HomeContentView();
        this.homeContentView.setContent(homeItem);

        VaadinUtils.getUi(this).access(() -> {
            setHeaderLabel(homeItem.displayName());
            setContent(this.homeContentView);
        });
    }
}
