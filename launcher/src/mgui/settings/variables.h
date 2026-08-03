#pragma once
#include <string>
#include <atomic>
#include "imgui.h"
#include <dwmapi.h>

struct ID3D11ShaderResourceView;

class c_variables
{
public:
	struct
	{
		HWND hwnd;
		RECT rc;
	} winapi;

	struct
	{
		std::string name{ "Kiwii" };
		ImGuiWindowFlags flags{ ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoNav | ImGuiWindowFlags_NoBackground };
		ImVec2 size{ 440, 460 };

		ImVec2 padding{ 0, 0 };
		ImVec2 spacing{ 18, 14 };
		float border_size{ 0 };
		float shadow_size{ 0 };
		float rounding{ 10 };

		ID3D11ShaderResourceView* glow_texture[2];
		ImVec2 glow_size{ 220, 220 };
	} window;

	struct
	{
		ImFont* inter[6];
		ImFont* icons[5];
	} font;

	struct
	{
		ImVec4 cross{ clr->title.cross };

		int current_stage{ 0 };
		int active_stage{ 0 };
		float stage_alpha{ 1 };

		float notify_alpha{ 0 };
		bool notify_condition{ false };
		float notify_hold_timer{ 0 };
		std::string notify_message{ "Login failed" };
		ImVec4 notify_color{ clr->notify.error };

		float loading_timer{ 0 };
		float injecting_timer{ 0 };

		char sign_in_login[128];
		char sign_in_password[128];
		int ram_index{ 3 };
		bool remember_me{ true };

		std::atomic<int> auth_job{ 0 };
		std::atomic<int> auth_result{ 0 };
	} gui;

	struct
	{
		std::string title{ "Kiwii" };
		std::string greetings{ "Sign in with your Craftrise account" };

		std::string success{ "Game launched" };
		std::string success_sub{ "Kiwii will inject once the JVM is ready" };

		std::string checking{ "Contacting Craftrise auth server" };
	} text;
};

inline c_variables* var = new c_variables();
