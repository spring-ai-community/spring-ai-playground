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
package org.springaicommunity.playground.webui.common;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.theme.lumo.LumoUtility;
import org.springaicommunity.playground.webui.VaadinUtils;

public abstract class WorkspaceSidebar extends VerticalLayout {

    private final Span titleSpan;
    private final MenuBar headerMenuBar;
    private final Div contentSlot;
    private final Header header;
    private Icon collapseChevron;
    private boolean collapsed = false;

    protected WorkspaceSidebar(String title) {
        setSpacing(false);
        setMargin(false);
        setPadding(false);
        setHeightFull();
        getStyle().set("overflow", "hidden");

        this.titleSpan = new Span(title);
        this.titleSpan.addClassNames(LumoUtility.FontWeight.SEMIBOLD, LumoUtility.FontSize.LARGE);

        this.headerMenuBar = new MenuBar();
        this.headerMenuBar.setWidthFull();
        this.headerMenuBar.addThemeVariants(MenuBarVariant.LUMO_END_ALIGNED,
                MenuBarVariant.LUMO_TERTIARY_INLINE);

        this.header = new Header(this.titleSpan, this.headerMenuBar);
        this.header.getStyle()
                .set("white-space", "nowrap")
                .set("width", "100%")
                .set("display", "flex")
                .set("box-sizing", "border-box")
                .set("align-items", "center")
                .set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                .set("flex", "0 0 auto");

        this.contentSlot = new Div();
        this.contentSlot.setWidthFull();
        this.contentSlot.getStyle()
                .set("flex", "1 1 auto")
                .set("min-height", "0")
                .set("overflow-y", "auto")
                .set("overflow-x", "hidden")
                .set("display", "flex")
                .set("flex-direction", "column");

        add(this.header, this.contentSlot);
    }

    protected MenuItem addHeaderIcon(VaadinIcon icon, String tooltip,
            ComponentEventListener<ClickEvent<MenuItem>> listener) {
        Icon iconInstance = VaadinUtils.styledIcon(icon.create());
        iconInstance.setTooltipText(tooltip);
        return this.headerMenuBar.addItem(iconInstance, listener);
    }

    protected void setSidebarTitle(String title) {
        this.titleSpan.setText(title);
    }

    public void enableCollapse() {
        if (this.collapseChevron != null) return;
        this.collapseChevron = new Icon(VaadinIcon.CHEVRON_DOWN);
        this.collapseChevron.setSize("0.85rem");
        this.collapseChevron.getStyle()
                .set("cursor", "pointer")
                .set("margin-right", "var(--lumo-space-s)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("flex", "0 0 auto");
        this.collapseChevron.addClickListener(e -> setCollapsed(!this.collapsed));

        this.titleSpan.getStyle().set("cursor", "pointer").set("user-select", "none");
        this.titleSpan.addClickListener(e -> setCollapsed(!this.collapsed));

        this.header.getElement().insertChild(0, this.collapseChevron.getElement());
        getStyle()
                .set("flex", "0 1 auto")
                .set("max-height", "50%")
                .set("min-height", "0");
        setHeight(null);
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        this.contentSlot.setVisible(!collapsed);
        if (this.collapseChevron != null) {
            this.collapseChevron.getElement().setAttribute("icon",
                    collapsed ? "vaadin:chevron-right" : "vaadin:chevron-down");
        }
        if (collapsed) {
            getStyle().set("flex", "0 0 auto").set("max-height", "none");
        } else {
            getStyle().set("flex", "0 1 auto").set("max-height", "50%");
        }
    }

    public boolean isCollapsed() {
        return this.collapsed;
    }

    protected void setSidebarContent(Component content) {
        this.contentSlot.removeAll();
        if (content != null) {
            this.contentSlot.add(content);
        }
    }

    protected static Scroller verticalScroller(Component content) {
        Scroller scroller = new Scroller(content);
        scroller.setSizeFull();
        scroller.setScrollDirection(Scroller.ScrollDirection.VERTICAL);
        return scroller;
    }

    protected static Span listItemText(String text) {
        Span span = new Span(text);
        span.getStyle()
                .set("white-space", "nowrap")
                .set("overflow", "hidden")
                .set("text-overflow", "ellipsis")
                .set("flex-grow", "1")
                .set("min-width", "0");
        return span;
    }
}
