#include "settings/functions.h"
#include "../util/config.hpp"
#include "../util/logger.hpp"
#include "../auth/launcher_api.hpp"
#include "../launch/rise_launch.hpp"

#include <thread>
#include <string>

static const int   RAM_OPTIONS[] = { 1024, 2048, 3072, 4096, 6144, 8192, 12288, 16384 };
static const char* RAM_LABELS[] = { "1024 MB", "2048 MB", "3072 MB", "4096 MB", "6144 MB", "8192 MB", "12288 MB", "16384 MB" };
static const int   RAM_COUNT = 8;

static void persistCredentials() {
    kiwii::config::setValue("rememberMe", var->gui.remember_me ? "true" : "false");
    kiwii::config::setValue("ram", std::to_string(RAM_OPTIONS[var->gui.ram_index]));
    if (var->gui.remember_me) {
        kiwii::config::setValue("username", var->gui.sign_in_login);
        kiwii::config::setValue("password", var->gui.sign_in_password);
    } else {
        kiwii::config::setValue("username", "");
        kiwii::config::setValue("password", "");
    }
}

static void loadCredentials() {
    auto m = kiwii::config::load();
    if (auto it = m.find("rememberMe"); it != m.end()) var->gui.remember_me = (it->second == "true");
    if (var->gui.remember_me) {
        if (auto it = m.find("username"); it != m.end()) strncpy(var->gui.sign_in_login,    it->second.c_str(), 127);
        if (auto it = m.find("password"); it != m.end()) strncpy(var->gui.sign_in_password, it->second.c_str(), 127);
    }
    if (auto it = m.find("ram"); it != m.end()) {
        try {
            int r = std::stoi(it->second);
            for (int i = 0; i < RAM_COUNT; i++) if (RAM_OPTIONS[i] == r) { var->gui.ram_index = i; break; }
        } catch (...) {}
    }
}

static void startAuthJob() {
    if (var->gui.auth_job.load()) return;
    var->gui.auth_job.store(1);
    var->gui.auth_result.store(0);
    persistCredentials();

    std::string user = var->gui.sign_in_login;
    std::string pass = var->gui.sign_in_password;
    int ram = RAM_OPTIONS[var->gui.ram_index];

    std::thread([user, pass, ram]() {
        kiwii::logger::info("Auth attempt for " + user);
        kiwii::auth::LoginResult lr = kiwii::auth::doLogin(user, pass);
        if (lr.globalSessionHash.empty()) {
            std::string msg = kiwii::auth::errorMessageForCode(lr.message);
            kiwii::logger::warn("Login failed for " + user + " — code=" + lr.message + " msg=" + msg);
            var->gui.notify_message = msg;
            var->gui.notify_color = clr->notify.error;
            var->gui.auth_result.store(2);
            var->gui.auth_job.store(0);
            return;
        }
        kiwii::logger::info("Auth OK status=" + lr.status + " kv=" + std::to_string(lr.keyValidatorDecoded.size()) + " chars");
        kiwii::launch::launchAndInject(user, pass, std::to_string(ram), lr.globalSessionHash,
                                       lr.keyValidatorDecoded, "C:\\kiwii\\kiwii.dll");
        var->gui.auth_result.store(1);
        var->gui.auth_job.store(0);
    }).detach();
}

static bool creds_loaded = false;

void c_gui::render()
{
    if (!creds_loaded) { loadCredentials(); creds_loaded = true; }

    gui->set_next_window_pos(ImVec2(0, 0));
    gui->set_next_window_size(var->window.size);
    gui->begin(var->window.name, nullptr, var->window.flags);
    {
        const ImVec2 pos = GetWindowPos();
        const ImVec2 size = GetWindowSize();
        ImDrawList* drawlist = GetWindowDrawList();
        ImGuiStyle* style = &GetStyle();

        {
            style->WindowPadding = var->window.padding;
            style->ItemSpacing = var->window.spacing;
            style->WindowBorderSize = var->window.border_size;
            style->WindowShadowSize = var->window.shadow_size;
            style->WindowRounding = var->window.rounding;
        }

        {
            draw->rect_filled(drawlist, pos, pos + size, draw->get_clr(clr->window.background), style->WindowRounding);

            draw->rect_filled_multi_color(drawlist, pos, ImVec2(pos.x + size.x, pos.y + 90),
                draw->get_clr(clr->accent, 0.10f), draw->get_clr(clr->accent, 0.03f),
                draw->get_clr(clr->window.background, 0.0f), draw->get_clr(clr->window.background, 0.0f),
                style->WindowRounding, ImDrawFlags_RoundCornersTop);

            draw->text_clipped(drawlist, var->font.inter[1], pos + ImVec2(28, 0), pos + ImVec2(size.x, 62), draw->get_clr(clr->accent), var->text.title.data(), NULL, NULL, ImVec2(0.f, 0.5f));

            bool close_hovered = IsMouseHoveringRect(pos + ImVec2(size.x - 44, 16), pos + ImVec2(size.x - 14, 46), true);
            var->gui.cross = ImLerp(var->gui.cross, close_hovered ? clr->notify.error : clr->title.cross, gui->fixed_speed(8.f));
            ImVec2 xc(pos.x + size.x - 29, pos.y + 31);
            draw->line(drawlist, ImVec2(xc.x - 6, xc.y - 6), ImVec2(xc.x + 6, xc.y + 6), draw->get_clr(var->gui.cross), 1.8f);
            draw->line(drawlist, ImVec2(xc.x - 6, xc.y + 6), ImVec2(xc.x + 6, xc.y - 6), draw->get_clr(var->gui.cross), 1.8f);
            if (close_hovered && IsMouseClicked(ImGuiMouseButton_Left))
                SendMessage(var->winapi.hwnd, WM_CLOSE, 0, 0);
        }

        {
            int done_result = var->gui.auth_result.load();
            if (var->gui.active_stage == 1 && done_result != 0) {
                if (done_result == 1) var->gui.current_stage = 2;
                else                  var->gui.current_stage = 0;
                if (done_result == 2) { var->gui.notify_condition = true; var->gui.notify_hold_timer = 0.f; }
                var->gui.auth_result.store(0);
            }

            var->gui.stage_alpha = ImClamp(var->gui.stage_alpha + (gui->fixed_speed(6.f) * (var->gui.current_stage == var->gui.active_stage ? 1.f : -1.f)), 0.f, 1.f);
            if (var->gui.notify_condition) {
                var->gui.notify_hold_timer += ImGui::GetIO().DeltaTime;
                if (var->gui.notify_hold_timer > 3.5f) { var->gui.notify_condition = false; var->gui.notify_hold_timer = 0.f; }
            }
            var->gui.notify_alpha = ImClamp(var->gui.notify_alpha + (gui->fixed_speed(6.f) * (var->gui.notify_condition ? 1.f : -1.f)), 0.f, 1.f);

            if (var->gui.stage_alpha == 0.f) {
                var->gui.active_stage = var->gui.current_stage;
                if (var->gui.current_stage != 0) {
                    var->gui.notify_alpha = 0.f;
                    var->gui.notify_condition = false;
                    var->gui.notify_hold_timer = 0.f;
                }
            }

            gui->push_style_var(ImGuiStyleVar_Alpha, var->gui.stage_alpha);

            if (var->gui.active_stage == 0) {
                draw->text_clipped(drawlist, var->font.inter[2], pos + ImVec2(0, 90), pos + size, draw->get_clr(clr->content.description), var->text.greetings.data(), NULL, NULL, ImVec2(0.5f, 0.f));

                gui->set_cursor_pos(ImVec2(70, 140));
                gui->begin_group();
                {
                    static int pending_focus = 0;
                    if (pending_focus == 1) { SetKeyboardFocusHere(); pending_focus = 0; }
                    gui->text_field("Username", "C", var->gui.sign_in_login, 128, ImVec2(300, 40));
                    bool user_active = IsItemActive();

                    if (pending_focus == 2) { SetKeyboardFocusHere(); pending_focus = 0; }
                    gui->text_field("Password", "D", var->gui.sign_in_password, 128, ImVec2(300, 40), ImGuiInputTextFlags_Password);
                    bool pass_active = IsItemActive();

                    if (user_active && IsKeyPressed(ImGuiKey_Tab, false)) pending_focus = 2;
                    if (pass_active && IsKeyPressed(ImGuiKey_Tab, false)) pending_focus = 1;

                    gui->set_cursor_pos(GetCursorPos() + ImVec2(0, 2));
                    draw->text(drawlist, var->font.inter[0], var->font.inter[0]->FontSize, ImVec2(pos.x + 70, GetCursorPosY()), draw->get_clr(clr->content.description), "RAM");
                    gui->set_cursor_pos(GetCursorPos() + ImVec2(0, 20));

                    if (gui->slider("##ram", &var->gui.ram_index, 0, RAM_COUNT - 1, RAM_LABELS[var->gui.ram_index], ImVec2(300, 24))) {
                        persistCredentials();
                    }

                    gui->set_cursor_pos(GetCursorPos() + ImVec2(0, 4));
                    gui->push_style_color(ImGuiCol_CheckMark, draw->get_clr(clr->accent));
                    gui->push_style_color(ImGuiCol_FrameBg, draw->get_clr(clr->textfield.stroke));
                    gui->push_style_color(ImGuiCol_FrameBgHovered, draw->get_clr(clr->content.line));
                    gui->push_style_color(ImGuiCol_FrameBgActive, draw->get_clr(clr->content.line));
                    gui->push_style_var(ImGuiStyleVar_FrameRounding, 4.f);
                    if (Checkbox("##remember", &var->gui.remember_me)) persistCredentials();
                    gui->pop_style_var();
                    gui->pop_style_color(4);
                    SameLine();
                    TextColored(clr->content.description, "Remember me");

                    gui->set_cursor_pos(ImVec2(70, GetCursorPos().y + 8));
                    bool auth_busy = var->gui.auth_job.load() != 0;
                    bool disabled = auth_busy || (strlen(var->gui.sign_in_login) == 0 || strlen(var->gui.sign_in_password) == 0);
                    if (disabled) gui->push_style_var(ImGuiStyleVar_Alpha, 0.5f);
                    bool clicked = gui->button(auth_busy ? "Signing in..." : "Login", ImVec2(300, 42));
                    if (disabled) gui->pop_style_var();
                    bool enter_pressed = (IsKeyPressed(ImGuiKey_Enter, false) || IsKeyPressed(ImGuiKey_KeypadEnter, false))
                                         && var->gui.active_stage == 0;
                    if ((clicked || enter_pressed) && !disabled) {
                        var->gui.notify_condition = false;
                        var->gui.notify_hold_timer = 0.f;
                        var->gui.current_stage = 1;
                        startAuthJob();
                    }

                    if (var->gui.notify_alpha > 0.02f) {
                        ImVec2 textSz = var->font.inter[4]->CalcTextSizeA(var->font.inter[4]->FontSize, FLT_MAX, -1.f, var->gui.notify_message.data());
                        float bannerW = textSz.x + 28.f;
                        float bannerH = textSz.y + 14.f;
                        float bx = pos.x + (size.x - bannerW) * 0.5f;
                        float by = GetCursorScreenPos().y - 2.f;
                        draw->rect_filled(drawlist, ImVec2(bx, by), ImVec2(bx + bannerW, by + bannerH), draw->get_clr(var->gui.notify_color, 0.18f * var->gui.notify_alpha), 8.f);
                        draw->rect(drawlist, ImVec2(bx, by), ImVec2(bx + bannerW, by + bannerH), draw->get_clr(var->gui.notify_color, 0.65f * var->gui.notify_alpha), 8.f, 0, 1.4f);
                        draw->text(drawlist, var->font.inter[4], var->font.inter[4]->FontSize,
                            ImVec2(bx + 14.f, by + (bannerH - textSz.y) * 0.5f + 1.f),
                            draw->get_clr(var->gui.notify_color, var->gui.notify_alpha),
                            var->gui.notify_message.data());
                    }
                }
                gui->end_group();
            }
            else if (var->gui.active_stage == 1) {
                gui->set_cursor_pos(ImVec2(size.x * 0.5f - 30, size.y * 0.38f));
                gui->loading();
                draw->text_clipped(drawlist, var->font.inter[2], pos + ImVec2(0, size.y - 175), pos + size, draw->get_clr(clr->content.description), var->text.checking.data(), NULL, NULL, ImVec2(0.5f, 0.f));
            }
            else if (var->gui.active_stage == 2) {
                int st = kiwii::launch::g_injectStatus.load();
                static float auto_close_timer = -1.f;
                if (st == 5 && auto_close_timer < 0.f) auto_close_timer = 3.0f;
                if (auto_close_timer > 0.f) {
                    auto_close_timer -= ImGui::GetIO().DeltaTime;
                    if (auto_close_timer <= 0.f) SendMessage(var->winapi.hwnd, WM_CLOSE, 0, 0);
                }

                const char* stageMsg;
                switch (st) {
                    case 1:  stageMsg = "Craftrise handshake in progress..."; break;
                    case 2:  stageMsg = "JVM booting... please wait 25s"; break;
                    case 3:  stageMsg = "Injecting Kiwii..."; break;
                    case 4:  stageMsg = "Injection failed"; break;
                    case 5:  stageMsg = "Kiwii injected - closing in 3s..."; break;
                    default: stageMsg = "Launching...";
                }
                bool done = (st >= 4);

                draw->text_clipped(drawlist, var->font.icons[3], pos + ImVec2(0, 40), pos + ImVec2(size.x, size.y - 130), draw->get_clr(clr->accent), "G", NULL, NULL, ImVec2(0.5f, 0.5f));
                draw->text_clipped(drawlist, var->font.inter[1], pos + ImVec2(0, size.y - 165), pos + size, draw->get_clr(st == 4 ? clr->notify.error : clr->accent), var->text.success.data(), NULL, NULL, ImVec2(0.5f, 0.f));
                draw->text_clipped(drawlist, var->font.inter[2], pos + ImVec2(0, size.y - 125), pos + size, draw->get_clr(clr->content.description), stageMsg, NULL, NULL, ImVec2(0.5f, 0.f));

                gui->set_cursor_pos(ImVec2(70, size.y - 72));
                if (!done) gui->push_style_var(ImGuiStyleVar_Alpha, 0.5f);
                bool clicked = gui->button(done ? "Close" : "Please wait...", ImVec2(300, 40));
                if (!done) gui->pop_style_var();
                if (clicked && done) SendMessage(var->winapi.hwnd, WM_CLOSE, 0, 0);
            }
            gui->pop_style_var();
        }

        gui->move_window(var->winapi.hwnd, var->winapi.rc);
    }
    gui->end();
}
