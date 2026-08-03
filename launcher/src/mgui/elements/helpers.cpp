#include "../settings/functions.h"

void c_gui::push_style_color(ImGuiCol idx, ImU32 col)
{
    PushStyleColor(idx, col);
}

void c_gui::pop_style_color(int count)
{
    PopStyleColor(count);
}

void c_gui::push_style_var(ImGuiStyleVar idx, float val)
{
    PushStyleVar(idx, val);
}

void c_gui::push_style_var(ImGuiStyleVar idx, const ImVec2& val)
{
    PushStyleVar(idx, val);
}

void c_gui::pop_style_var(int count)
{
    PopStyleVar(count);
}

void c_gui::push_font(ImFont* font)
{
    ImGui::PushFont(font);
}

void c_gui::pop_font()
{
    ImGui::PopFont();
}

void c_gui::set_cursor_pos(const ImVec2& local_pos)
{
    SetCursorPos(local_pos);
}

void c_gui::begin_group()
{
    BeginGroup();
}

void c_gui::end_group()
{
    EndGroup();
}

void c_gui::begin_content()
{
    gui->begin_def_child("content area", ImVec2(0, 0), ImGuiChildFlags_None, ImGuiWindowFlags_NoNav | ImGuiWindowFlags_AlwaysUseWindowPadding | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoMove);
}

void c_gui::end_content()
{
    gui->end_def_child();
}

void c_gui::sameline()
{
    SameLine(0.f, -1.f);
}

bool begin_def_child_ex(const char* name, ImGuiID id, const ImVec2& size_arg, ImGuiChildFlags child_flags, ImGuiWindowFlags window_flags)
{
    ImGuiContext& g = *GImGui;
    ImGuiWindow* parent_window = g.CurrentWindow;
    IM_ASSERT(id != 0);

    // Sanity check as it is likely that some user will accidentally pass ImGuiWindowFlags into the ImGuiChildFlags argument.
    const ImGuiChildFlags ImGuiChildFlags_SupportedMask_ = ImGuiChildFlags_Border | ImGuiChildFlags_AlwaysUseWindowPadding | ImGuiChildFlags_ResizeX | ImGuiChildFlags_ResizeY | ImGuiChildFlags_AutoResizeX | ImGuiChildFlags_AutoResizeY | ImGuiChildFlags_AlwaysAutoResize | ImGuiChildFlags_FrameStyle;
    IM_UNUSED(ImGuiChildFlags_SupportedMask_);
    IM_ASSERT((child_flags & ~ImGuiChildFlags_SupportedMask_) == 0 && "Illegal ImGuiChildFlags value. Did you pass ImGuiWindowFlags values instead of ImGuiChildFlags?");
    IM_ASSERT((window_flags & ImGuiWindowFlags_AlwaysAutoResize) == 0 && "Cannot specify ImGuiWindowFlags_AlwaysAutoResize for BeginChild(). Use ImGuiChildFlags_AlwaysAutoResize!");
    if (child_flags & ImGuiChildFlags_AlwaysAutoResize)
    {
        IM_ASSERT((child_flags & (ImGuiChildFlags_ResizeX | ImGuiChildFlags_ResizeY)) == 0 && "Cannot use ImGuiChildFlags_ResizeX or ImGuiChildFlags_ResizeY with ImGuiChildFlags_AlwaysAutoResize!");
        IM_ASSERT((child_flags & (ImGuiChildFlags_AutoResizeX | ImGuiChildFlags_AutoResizeY)) != 0 && "Must use ImGuiChildFlags_AutoResizeX or ImGuiChildFlags_AutoResizeY with ImGuiChildFlags_AlwaysAutoResize!");
    }
#ifndef IMGUI_DISABLE_OBSOLETE_FUNCTIONS
    if (window_flags & ImGuiWindowFlags_AlwaysUseWindowPadding)
        child_flags |= ImGuiChildFlags_AlwaysUseWindowPadding;
#endif
    if (child_flags & ImGuiChildFlags_AutoResizeX)
        child_flags &= ~ImGuiChildFlags_ResizeX;
    if (child_flags & ImGuiChildFlags_AutoResizeY)
        child_flags &= ~ImGuiChildFlags_ResizeY;

    // Set window flags
    window_flags |= ImGuiWindowFlags_ChildWindow | ImGuiWindowFlags_NoTitleBar;
    window_flags |= (parent_window->Flags & ImGuiWindowFlags_NoMove); // Inherit the NoMove flag
    if (child_flags & (ImGuiChildFlags_AutoResizeX | ImGuiChildFlags_AutoResizeY | ImGuiChildFlags_AlwaysAutoResize))
        window_flags |= ImGuiWindowFlags_AlwaysAutoResize;
    if ((child_flags & (ImGuiChildFlags_ResizeX | ImGuiChildFlags_ResizeY)) == 0)
        window_flags |= ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoSavedSettings;

    // Special framed style
    if (child_flags & ImGuiChildFlags_FrameStyle)
    {
        PushStyleColor(ImGuiCol_ChildBg, g.Style.Colors[ImGuiCol_FrameBg]);
        PushStyleVar(ImGuiStyleVar_ChildRounding, g.Style.FrameRounding);
        PushStyleVar(ImGuiStyleVar_ChildBorderSize, g.Style.FrameBorderSize);
        PushStyleVar(ImGuiStyleVar_WindowPadding, g.Style.FramePadding);
        child_flags |= ImGuiChildFlags_Border | ImGuiChildFlags_AlwaysUseWindowPadding;
        window_flags |= ImGuiWindowFlags_NoMove;
    }

    // Forward child flags
    g.NextWindowData.Flags |= ImGuiNextWindowDataFlags_HasChildFlags;
    g.NextWindowData.ChildFlags = child_flags;

    // Forward size
    // Important: Begin() has special processing to switch condition to ImGuiCond_FirstUseEver for a given axis when ImGuiChildFlags_ResizeXXX is set.
    // (the alternative would to store conditional flags per axis, which is possible but more code)
    const ImVec2 size_avail = GetContentRegionAvail();
    const ImVec2 size_default((child_flags & ImGuiChildFlags_AutoResizeX) ? 0.0f : size_avail.x, (child_flags & ImGuiChildFlags_AutoResizeY) ? 0.0f : size_avail.y);
    const ImVec2 size = CalcItemSize(size_arg, size_default.x, size_default.y);
    SetNextWindowSize(size);

    // Build up name. If you need to append to a same child from multiple location in the ID stack, use BeginChild(ImGuiID id) with a stable value.
    // FIXME: 2023/11/14: commented out shorted version. We had an issue with multiple ### in child window path names, which the trailing hash helped workaround.
    // e.g. "ParentName###ParentIdentifier/ChildName###ChildIdentifier" would get hashed incorrectly by ImHashStr(), trailing _%08X somehow fixes it.
    const char* temp_window_name;
    /*if (name && parent_window->IDStack.back() == parent_window->ID)
        ImFormatStringToTempBuffer(&temp_window_name, NULL, "%s/%s", parent_window->Name, name); // May omit ID if in root of ID stack
    else*/
    if (name)
        ImFormatStringToTempBuffer(&temp_window_name, NULL, "%s/%s_%08X", parent_window->Name, name, id);
    else
        ImFormatStringToTempBuffer(&temp_window_name, NULL, "%s/%08X", parent_window->Name, id);

    // Set style
    const float backup_border_size = g.Style.ChildBorderSize;
    if ((child_flags & ImGuiChildFlags_Border) == 0)
        g.Style.ChildBorderSize = 0.0f;

    // Begin into window
    const bool ret = gui->begin(temp_window_name, NULL, window_flags);

    // Restore style
    g.Style.ChildBorderSize = backup_border_size;
    if (child_flags & ImGuiChildFlags_FrameStyle)
    {
        PopStyleVar(3);
        PopStyleColor();
    }

    ImGuiWindow* child_window = g.CurrentWindow;
    child_window->ChildId = id;

    // Set the cursor to handle case where the user called SetNextWindowPos()+BeginChild() manually.
    // While this is not really documented/defined, it seems that the expected thing to do.
    if (child_window->BeginCount == 1)
        parent_window->DC.CursorPos = child_window->Pos;

    // Process navigation-in immediately so NavInit can run on first frame
    // Can enter a child if (A) it has navigable items or (B) it can be scrolled.
    const ImGuiID temp_id_for_activation = ImHashStr("##Child", 0, id);
    if (g.ActiveId == temp_id_for_activation)
        ClearActiveID();
    if (g.NavActivateId == id && !(window_flags & ImGuiWindowFlags_NavFlattened) && (child_window->DC.NavLayersActiveMask != 0 || child_window->DC.NavWindowHasScrollY))
    {
        FocusWindow(child_window);
        NavInitWindow(child_window, false);
        SetActiveID(temp_id_for_activation, child_window); // Steal ActiveId with another arbitrary id so that key-press won't activate child item
        g.ActiveIdSource = g.NavInputSource;
    }
    return ret;
}

bool c_gui::begin_def_child(std::string_view name, const ImVec2& size_arg, ImGuiChildFlags child_flags, ImGuiWindowFlags window_flags)
{
    ImGuiID id = GetCurrentWindow()->GetID(name.data());
    return begin_def_child_ex(name.data(), id, size_arg, child_flags, window_flags);
}

void c_gui::end_def_child()
{
    ImGuiContext& g = *GImGui;
    ImGuiWindow* child_window = g.CurrentWindow;

    IM_ASSERT(g.WithinEndChild == false);
    IM_ASSERT(child_window->Flags & ImGuiWindowFlags_ChildWindow);   // Mismatched BeginChild()/EndChild() calls

    g.WithinEndChild = true;
    ImVec2 child_size = child_window->Size;
    gui->end();
    if (child_window->BeginCount == 1)
    {
        ImGuiWindow* parent_window = g.CurrentWindow;
        ImRect bb(parent_window->DC.CursorPos, parent_window->DC.CursorPos + child_size);
        ItemSize(child_size);
        if ((child_window->DC.NavLayersActiveMask != 0 || child_window->DC.NavWindowHasScrollY) && !(child_window->Flags & ImGuiWindowFlags_NavFlattened))
        {
            ItemAdd(bb, child_window->ChildId);
            RenderNavHighlight(bb, child_window->ChildId);

            // When browsing a window that has no activable items (scroll only) we keep a highlight on the child (pass g.NavId to trick into always displaying)
            if (child_window->DC.NavLayersActiveMask == 0 && child_window == g.NavWindow)
                RenderNavHighlight(ImRect(bb.Min - ImVec2(2, 2), bb.Max + ImVec2(2, 2)), g.NavId, ImGuiNavHighlightFlags_Compact);
        }
        else
        {
            // Not navigable into
            ItemAdd(bb, 0);

            // But when flattened we directly reach items, adjust active layer mask accordingly
            if (child_window->Flags & ImGuiWindowFlags_NavFlattened)
                parent_window->DC.NavLayersActiveMaskNext |= child_window->DC.NavLayersActiveMaskNext;
        }
        if (g.HoveredWindow == child_window)
            g.LastItemData.StatusFlags |= ImGuiItemStatusFlags_HoveredWindow;
    }
    g.WithinEndChild = false;
    g.LogLinePosY = -FLT_MAX; // To enforce a carriage return
}

void c_gui::set_next_window_pos(const ImVec2& pos, ImGuiCond cond, const ImVec2& pivot)
{
    SetNextWindowPos(pos, cond, pivot);
}

void c_gui::set_next_window_size(const ImVec2& size, ImGuiCond cond)
{
    SetNextWindowSize(size, cond);
}

void c_gui::loading()
{
    const ImVec2 pos = GetCursorScreenPos();
    ImDrawList* drawlist = GetWindowDrawList();
    const ImVec2 size = ImVec2(10, 30);

    static float offset[3] = {0, 0, 0};
    static float alpha[3] = { 0, 0, 0 };
    static float timer = 0.f;
    float spacing = 0;
    int bar_count = 3;

    timer += gui->fixed_speed(4.f);
    if (timer >= 5.f * bar_count)
        timer = 0.f;

    for (int i = 0; i < bar_count; i++)
    {
        bool active = timer >= 5.f * i && timer <= 5.f * (i + 1);
        offset[i] = ImLerp(offset[i], active ? 6.f : 0.f, gui->fixed_speed(12.f));
        alpha[i] = ImClamp(alpha[i] + (gui->fixed_speed(8.f) * (!active ? 1.f : -1.f)), 0.f, 1.f);

        draw->rect_filled(drawlist, pos + ImVec2(spacing, -offset[i]), pos + size + ImVec2(spacing, offset[i]), draw->get_clr(clr->accent), 5.f);
        draw->rect_filled(drawlist, pos + ImVec2(spacing, -offset[i]), pos + size + ImVec2(spacing, offset[i]), draw->get_clr({0.f, 0.f, 0.f, 0.3f * alpha[i]}), 5.f);

        spacing += 25;
    }

}

void c_gui::game_selection(ImTextureID texture, const char* name, int vid, int var)
{
    struct game_selection_state
    {
        float alpha{ 0 };
        float size_x{ 0 };
        float size_y{ 0 };
        float padding{ 0 };
        float rotate{ 0 };
    };

    ImGuiWindow* window = GetCurrentWindow();
    if (window->SkipItems)
        return;

    ImGuiContext& g = *GImGui;
    const ImGuiStyle& style = g.Style;
    const ImGuiID id = window->GetID(name);

    const ImVec2 pos = window->DC.CursorPos;
    const ImRect rect(pos, pos + ImVec2(110, 150));
    ItemSize(rect, style.FramePadding.y);
    if (!ItemAdd(rect, id))
        return;

    bool hovered, held;
    bool pressed = ButtonBehavior(rect, id, &hovered, &held, 0);

    // Render
    game_selection_state* state = gui->anim_container(&state, id);
    state->alpha = ImClamp(state->alpha + (gui->fixed_speed(6.f) * (var != vid ? 1.f : -1.f)), 0.f, 1.f);
    state->size_x = ImLerp(state->size_x, var != vid ? 15 : 0.f, gui->fixed_speed(12.f));
    state->size_y = ImLerp(state->size_y, var != vid ? std::abs(vid - var) > 1 ? 60 : 30 : 0.f, gui->fixed_speed(12.f));
    state->padding = ImLerp(state->padding, std::abs(vid - var) > 1 ? 15.f : 0.f, gui->fixed_speed(12.f));
    state->rotate = ImLerp(state->rotate, (var - vid) > 0 ? std::abs(vid - var) > 1  ? -15.f : -10.f : (var - vid) < 0 ? std::abs(vid - var) > 1 ? 15.f : 10.f : 0.f, gui->fixed_speed(12.f));

    draw->rotate_start();
    draw->image_rounded(window->DrawList, texture, rect.Min + ImVec2(state->size_x, state->size_y + state->padding), rect.Max - ImVec2(state->size_x, -state->padding), ImVec2(0, 0), ImVec2(1, 1), draw->get_clr({1.f, 1.f, 1.f, 1.f}), 8);
    draw->rect_filled(window->DrawList, rect.Min + ImVec2(state->size_x, state->size_y + state->padding), rect.Max - ImVec2(state->size_x, -state->padding), draw->get_clr({ 0.f, 0.f, 0.f, 0.5f * state->alpha }), 8.f);
    draw->rotate_end(draw->deg_to_rad(90.f - state->rotate));
}


void c_gui::move_window(HWND hwnd, RECT rc)
{
    SetCursorPos(ImVec2(0, 0));
    InvisibleButton("##MOVEBUTTON", ImVec2(GetWindowWidth(), elements->title.height));
    if (IsItemActive())
    {
        GetWindowRect(hwnd, &rc);
        MoveWindow(hwnd, rc.left + GetMouseDragDelta().x, rc.top + GetMouseDragDelta().y, GetWindowSize().x, GetWindowSize().y, FALSE);
    }
}
