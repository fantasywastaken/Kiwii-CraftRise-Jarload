#include "../settings/functions.h"

bool c_gui::slider(std::string_view label, int* value, int v_min, int v_max, const char* display_text, const ImVec2& size)
{
    ImGuiWindow* window = GetCurrentWindow();
    if (window->SkipItems)
        return false;

    ImGuiContext& g = *GImGui;
    const ImGuiStyle& style = g.Style;
    const ImGuiID id = window->GetID(label.data());

    const ImVec2 pos = window->DC.CursorPos;
    const ImRect frame_rect(pos, pos + size);
    ItemSize(frame_rect, style.FramePadding.y);
    if (!ItemAdd(frame_rect, id))
        return false;

    struct slider_state { float glow{ 0 }; };
    slider_state* state = gui->anim_container(&state, id);

    bool hovered = ItemHoverable(frame_rect, id, 0);
    bool active = g.ActiveId == id;
    bool held = false;
    bool changed = false;

    if (hovered && IsMouseClicked(ImGuiMouseButton_Left)) {
        SetActiveID(id, window);
        SetFocusID(id, window);
        FocusWindow(window);
    }

    if (active) {
        if (IsMouseDown(ImGuiMouseButton_Left)) {
            held = true;
            float t = ImClamp((GetMousePos().x - frame_rect.Min.x) / size.x, 0.f, 1.f);
            int new_v = v_min + (int)std::round(t * (v_max - v_min));
            if (new_v != *value) { *value = new_v; changed = true; }
        } else {
            ClearActiveID();
        }
    }

    state->glow = ImLerp(state->glow, (hovered || active) ? 1.f : 0.f, gui->fixed_speed(8.f));

    const float track_h = 6.f;
    ImVec2 track_min(frame_rect.Min.x, frame_rect.Min.y + (size.y - track_h) * 0.5f);
    ImVec2 track_max(frame_rect.Max.x, track_min.y + track_h);

    draw->rect_filled(window->DrawList, track_min, track_max, draw->get_clr(clr->textfield.stroke), track_h * 0.5f);

    float t = (v_max > v_min) ? ((float)(*value - v_min) / (float)(v_max - v_min)) : 0.f;
    float fill_x = track_min.x + t * size.x;
    draw->rect_filled(window->DrawList, track_min, ImVec2(fill_x, track_max.y), draw->get_clr(clr->accent), track_h * 0.5f);

    float grab_r = 9.f + state->glow * 2.f;
    ImVec2 grab_center(fill_x, (track_min.y + track_max.y) * 0.5f);
    draw->circle_filled(window->DrawList, grab_center, grab_r + 3.f, draw->get_clr(clr->accent, 0.15f * state->glow), 24);
    draw->circle_filled(window->DrawList, grab_center, grab_r, draw->get_clr(clr->window.background), 24);
    draw->circle_filled(window->DrawList, grab_center, grab_r - 2.f, draw->get_clr(clr->accent), 24);

    ImVec2 label_size = var->font.inter[0]->CalcTextSizeA(var->font.inter[0]->FontSize, FLT_MAX, -1.f, display_text);
    draw->text(window->DrawList, var->font.inter[0], var->font.inter[0]->FontSize,
               ImVec2(frame_rect.Max.x - label_size.x, frame_rect.Min.y - 20),
               draw->get_clr(clr->accent), display_text);

    return changed;
}
