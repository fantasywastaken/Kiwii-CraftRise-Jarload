#pragma once
#include <string>
#include "imgui.h"

class c_elements
{
public:
	struct
	{
		float height{ 60.f };
		float padding{ 80.f };
	} title;
};

inline c_elements* elements = new c_elements();