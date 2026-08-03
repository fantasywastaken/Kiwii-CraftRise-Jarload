#pragma once
#include "imgui.h"

class c_colors
{
public:

	ImColor accent{ 130, 199, 87 };

	struct
	{
		ImColor background{ 14, 18, 14 };
		ImColor glow{ 24, 34, 22 };
	} window;

	struct
	{
		ImColor label{ 235, 240, 230 };
		ImColor cross{ 165, 175, 160 };
	} title;

	struct
	{
		ImColor text{ 235, 240, 230 };
		ImColor description{ 128, 140, 122 };
		ImColor line{ 32, 42, 30 };
	} content;

	struct
	{
		ImColor error{ 235, 92, 84 };
		ImColor success{ 130, 199, 87 };
	} notify;

	struct
	{
		ImColor text{ 235, 240, 230 };
		ImColor text_inactive{ 128, 140, 122 };
		ImColor stroke{ 32, 42, 30 };
	} textfield;

	struct
	{
		ImColor text{ 15, 22, 15 };
		ImColor glow{ 130, 199, 87 };

		ImColor backgroud{ 34, 44, 32 };
	} button;
};

inline c_colors* clr = new c_colors();
