#pragma once

#define IMGUI_DEFINE_MATH_OPERATORS

#include "imgui.h"
#include "imgui_internal.h"
#include "colors.h"
#include "variables.h"
#include "elements.h"
#include <vector>
#include <sstream>
#include <string>

using namespace ImGui;

class c_gui
{
public:

    template <typename T>
    T* anim_container(T** state_ptr, ImGuiID id)
    {
        T* state = static_cast<T*>(GetStateStorage()->GetVoidPtr(id));
        if (!state)
            GetStateStorage()->SetVoidPtr(id, state = new T());

        *state_ptr = state;
        return state;
    }

    float fixed_speed(float speed) { return speed / ImGui::GetIO().Framerate; };

    bool begin(std::string_view name, bool* p_open = nullptr, ImGuiWindowFlags flags = ImGuiWindowFlags_None);

    void end();

    void push_style_color(ImGuiCol idx, ImU32 col);

    void pop_style_color(int count = 1);

    void push_style_var(ImGuiStyleVar idx, float val);

    void push_style_var(ImGuiStyleVar idx, const ImVec2& val);

    void pop_style_var(int count = 1);

    void push_font(ImFont* font);

    void pop_font();

    void set_cursor_pos(const ImVec2& local_pos);

    void begin_group();

    void end_group();

    void begin_content();

    void end_content();

    void sameline();

    bool begin_def_child(std::string_view name, const ImVec2& size_arg = ImVec2(0, 0), ImGuiChildFlags child_flags = 0, ImGuiWindowFlags window_flags = 0);

    void end_def_child();

    void set_next_window_pos(const ImVec2& pos, ImGuiCond cond = 0, const ImVec2& pivot = ImVec2(0, 0));

    void set_next_window_size(const ImVec2& size, ImGuiCond cond = 0);

    bool text_field(std::string_view label, std::string_view icon, char* buf, size_t buf_size, const ImVec2& size, ImGuiInputTextFlags flags = 0);

    bool button(std::string_view label, const ImVec2& size);

    bool slider(std::string_view label, int* value, int v_min, int v_max, const char* display_text, const ImVec2& size);

    bool icon_button(std::string_view icon, const ImVec2& size);

    bool social_button(std::string_view icon);

    void loading();

    void game_selection(ImTextureID texture, const char* name, int vid, int var);

    void move_window(HWND hwnd, RECT rc);

    void render();

};

inline c_gui* gui = new c_gui();

enum fade_direction : int
{
    vertically,
    horizontally,
    diagonally,
    diagonally_reversed,
};

class c_draw
{
public:
    ImU32 get_clr(const ImVec4& col, float alpha = 1.f);

    void text(ImDrawList* draw_list, const ImFont* font, float font_size, const ImVec2& pos, ImU32 col, const char* text_begin, const char* text_end = NULL, float wrap_width = NULL, const ImVec4* cpu_fine_clip_rect = 0);

    void text_clipped(ImDrawList* draw_list, ImFont* font, const ImVec2& pos_min, const ImVec2& pos_max, ImU32 color, const char* text, const char* text_display_end = NULL, const ImVec2* text_size_if_known = NULL, const ImVec2& align = ImVec2(0.f, 0.f), const ImRect* clip_rect = NULL);

    void radial_gradient(ImDrawList* draw_list, const ImVec2& center, float radius, ImU32 col_in, ImU32 col_out);

    void set_linear_color_alpha(ImDrawList* draw_list, int vert_start_idx, int vert_end_idx, ImVec2 gradient_p0, ImVec2 gradient_p1, ImU32 col0, ImU32 col1);

    void line(ImDrawList* draw_list, const ImVec2& p1, const ImVec2& p2, ImU32 col, float thickness = 1.0f);

    void rect(ImDrawList* draw_list, const ImVec2& p_min, const ImVec2& p_max, ImU32 col, float rounding = 0.0f, ImDrawFlags flags = 0, float thickness = 1.0f);

    void rect_filled(ImDrawList* draw_list, const ImVec2& p_min, const ImVec2& p_max, ImU32 col, float rounding = 0.0f, ImDrawFlags flags = 0);

    void rect_filled_multi_color(ImDrawList* draw, const ImVec2& p_min, const ImVec2& p_max, ImU32 col_upr_left, ImU32 col_upr_right, ImU32 col_bot_right, ImU32 col_bot_left, float rounding = 0.f, ImDrawFlags flags = 0);

    void fade_rect_filled(ImDrawList* draw, const ImVec2& pos_min, const ImVec2& pos_max, ImU32 col_one, ImU32 col_two, fade_direction direction, float rounding = 0.f, ImDrawFlags flags = 0);

    void shadow_rect(ImDrawList* draw_list, const ImVec2& obj_min, const ImVec2& obj_max, ImU32 shadow_col, float shadow_thickness, const ImVec2& shadow_offset, ImDrawFlags flags = 0, float obj_rounding = 0.0f);

    void circle(ImDrawList* draw_list, const ImVec2& center, float radius, ImU32 col, int num_segments = 0, float thickness = 1.0f);

    void circle_filled(ImDrawList* draw_list, const ImVec2& center, float radius, ImU32 col, int num_segments = 0);

    void shadow_circle(ImDrawList* draw_list, const ImVec2& obj_center, float obj_radius, ImU32 shadow_col, float shadow_thickness, const ImVec2& shadow_offset, ImDrawFlags flags = 0, int obj_num_segments = 12);

    void triangle(ImDrawList* draw_list, const ImVec2& p1, const ImVec2& p2, const ImVec2& p3, ImU32 col, float thickness = 1.f);

    void triangle_filled(ImDrawList* draw_list, const ImVec2& p1, const ImVec2& p2, const ImVec2& p3, ImU32 col);

    void image(ImDrawList* draw_list, ImTextureID user_texture_id, const ImVec2& p_min, const ImVec2& p_max, const ImVec2& uv_min = ImVec2(0, 0), const ImVec2& uv_max = ImVec2(1, 1), ImU32 col = IM_COL32_WHITE);

    void image_rounded(ImDrawList* draw_list, ImTextureID user_texture_id, const ImVec2& p_min, const ImVec2& p_max, const ImVec2& uv_min = ImVec2(0, 0), const ImVec2& uv_max = ImVec2(1, 1), ImU32 col = IM_COL32_WHITE, float rounding = 1.f, ImDrawFlags flags = 0);
    
    void shadow_convex_poly(ImDrawList* draw_list, const ImVec2* points, int points_count, ImU32 shadow_col, float shadow_thickness, const ImVec2& shadow_offset, ImDrawFlags flags = 0);
    
    void shadow_ngon(ImDrawList* draw_list, const ImVec2& obj_center, float obj_radius, ImU32 shadow_col, float shadow_thickness, const ImVec2& shadow_offset, ImDrawFlags flags, int obj_num_segments);

    void rotate_start();

    void rotate_end(float rad, ImVec2 center = ImVec2(0, 0));

    float deg_to_rad(float deg);

};

inline c_draw* draw = new c_draw();