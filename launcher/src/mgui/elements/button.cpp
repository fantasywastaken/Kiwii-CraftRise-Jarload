#include "../settings/functions.h"

bool c_gui::button(std::string_view label, const ImVec2& size)
{
    struct button_state
    {
        bool clicked{ false };
        float alpha{ 0 };
        ImVec2 mouse_pos{ 0, 0 };
    };

    ImGuiWindow* window = GetCurrentWindow();
    if (window->SkipItems)
        return false;

    ImGuiContext& g = *GImGui;
    const ImGuiStyle& style = g.Style;
    const ImGuiID id = window->GetID(label.data());

    const ImVec2 pos = window->DC.CursorPos;
    const ImRect rect(pos, pos + size);
    ItemSize(rect, style.FramePadding.y);
    if (!ItemAdd(rect, id))
        return false;

    bool hovered, held;
    bool pressed = ButtonBehavior(rect, id, &hovered, &held, 0);

    // Render
    button_state* state = gui->anim_container(&state, id);

    if (pressed || held)
    {
        if (rect.Contains(GetMousePos()))
            state->mouse_pos = GetMousePos();
        state->clicked = true;
    }

    state->alpha = ImClamp(state->alpha + (gui->fixed_speed(8.f) * (state->clicked ? 1.f : -1.f)), 0.f, 1.f);

    if (state->alpha >= 0.9f)
        state->clicked = false;

    const float br = 10.f;
    draw->rect_filled(window->DrawList, rect.Min, rect.Max, draw->get_clr(clr->accent), br);
    draw->rect_filled_multi_color(window->DrawList, rect.Min, rect.Max,
        draw->get_clr({ 1.f, 1.f, 1.f, 0.12f }), draw->get_clr({ 1.f, 1.f, 1.f, 0.02f }),
        draw->get_clr({ 0.f, 0.f, 0.f, 0.12f }), draw->get_clr({ 0.f, 0.f, 0.f, 0.02f }),
        br);

    if (state->alpha > 0.01f) {
        float radius = size.x * 0.9f * state->alpha;
        draw->circle_filled(window->DrawList, state->mouse_pos, radius, draw->get_clr(clr->button.glow, 0.18f * (1.f - state->alpha)), 32);
    }

    draw->text_clipped(window->DrawList, var->font.inter[3], rect.Min, rect.Max, draw->get_clr(clr->button.text), label.data(), NULL, NULL, ImVec2(0.5f, 0.5f));

    IMGUI_TEST_ENGINE_ITEM_INFO(id, label, g.LastItemData.StatusFlags);
    return pressed;
}

bool c_gui::icon_button(std::string_view icon, const ImVec2& size)
{
    struct button_state
    {
        bool clicked{ false };
        float alpha{ 0 };
        ImVec4 col{ clr->content.description };
    };

    ImGuiWindow* window = GetCurrentWindow();
    if (window->SkipItems)
        return false;

    ImGuiContext& g = *GImGui;
    const ImGuiStyle& style = g.Style;
    const ImGuiID id = window->GetID(icon.data());

    const ImVec2 pos = window->DC.CursorPos;
    const ImRect rect(pos, pos + size);
    ItemSize(rect, style.FramePadding.y);
    if (!ItemAdd(rect, id))
        return false;

    bool hovered, held;
    bool pressed = ButtonBehavior(rect, id, &hovered, &held, 0);

    // Render
    button_state* state = gui->anim_container(&state, id);

    if (pressed)
        state->clicked = true;

    state->alpha = ImClamp(state->alpha + (gui->fixed_speed(8.f) * (state->clicked ? 1.f : -1.f)), 0.f, 1.f);
    state->col = ImLerp(state->col, state->clicked ? clr->accent : clr->content.description, gui->fixed_speed(12.f));

    if (state->alpha >= 0.9f)
        state->clicked = false;

    draw->text_clipped(window->DrawList, var->font.icons[4], rect.Min, rect.Max, draw->get_clr(state->col), icon.data(), NULL, NULL, ImVec2(0.5f, 0.5f));


    IMGUI_TEST_ENGINE_ITEM_INFO(id, label, g.LastItemData.StatusFlags);
    return pressed;
}

bool c_gui::social_button(std::string_view icon)
{
    ImGuiWindow* window = GetCurrentWindow();
    if (window->SkipItems)
        return false;

    ImGuiContext& g = *GImGui;
    const ImGuiStyle& style = g.Style;
    const ImGuiID id = window->GetID((std::stringstream{} << window->Name << icon).str().c_str());

    const ImVec2 pos = window->DC.CursorPos;
    const ImRect rect(pos, pos + ImVec2(20, 20));
    ItemSize(rect, style.FramePadding.y);
    if (!ItemAdd(rect, id))
        return false;

    bool hovered, held;
    bool pressed = ButtonBehavior(rect, id, &hovered, &held, 0);

    // Render
    ImVec4* state = gui->anim_container(&state, id);

    *state = ImLerp(*state, hovered ? clr->accent : clr->content.description, gui->fixed_speed(8.f));

    draw->text_clipped(window->DrawList, var->font.icons[0], rect.Min, rect.Max, draw->get_clr(*state), icon.data(), NULL, NULL, ImVec2(0.5f, 0.5f));

    IMGUI_TEST_ENGINE_ITEM_INFO(id, label, g.LastItemData.StatusFlags);
    return pressed;
}