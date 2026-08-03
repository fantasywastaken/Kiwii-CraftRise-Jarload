#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <windowsx.h>
#include <d3d11.h>
#include <tchar.h>

#include "../vendor/imgui/imgui.h"
#include "../vendor/imgui/backends/imgui_impl_win32.h"
#include "../vendor/imgui/backends/imgui_impl_dx11.h"

#include "mgui/settings/functions.h"
#include "mgui/data/fonts.h"

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")

static ID3D11Device*            g_pd3dDevice           = nullptr;
static ID3D11DeviceContext*     g_pd3dDeviceContext    = nullptr;
static IDXGISwapChain*          g_pSwapChain           = nullptr;
static ID3D11RenderTargetView*  g_mainRenderTargetView = nullptr;

extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND, UINT, WPARAM, LPARAM);

static bool CreateRenderTarget() {
    ID3D11Texture2D* backBuffer = nullptr;
    g_pSwapChain->GetBuffer(0, IID_PPV_ARGS(&backBuffer));
    if (!backBuffer) return false;
    g_pd3dDevice->CreateRenderTargetView(backBuffer, nullptr, &g_mainRenderTargetView);
    backBuffer->Release();
    return true;
}

static void CleanupRenderTarget() {
    if (g_mainRenderTargetView) { g_mainRenderTargetView->Release(); g_mainRenderTargetView = nullptr; }
}

static bool CreateDeviceD3D(HWND hWnd) {
    DXGI_SWAP_CHAIN_DESC sd{};
    sd.BufferCount = 2;
    sd.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    sd.BufferDesc.RefreshRate.Numerator = 60;
    sd.BufferDesc.RefreshRate.Denominator = 1;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.OutputWindow = hWnd;
    sd.SampleDesc.Count = 1;
    sd.SampleDesc.Quality = 0;
    sd.Windowed = TRUE;
    sd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;

    D3D_FEATURE_LEVEL fl;
    const D3D_FEATURE_LEVEL levels[] = { D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_0 };
    HRESULT hr = D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0,
        levels, 2, D3D11_SDK_VERSION, &sd, &g_pSwapChain, &g_pd3dDevice, &fl, &g_pd3dDeviceContext);
    if (hr != S_OK) return false;
    return CreateRenderTarget();
}

static void CleanupDeviceD3D() {
    CleanupRenderTarget();
    if (g_pSwapChain)        { g_pSwapChain->Release();        g_pSwapChain = nullptr; }
    if (g_pd3dDeviceContext) { g_pd3dDeviceContext->Release(); g_pd3dDeviceContext = nullptr; }
    if (g_pd3dDevice)        { g_pd3dDevice->Release();        g_pd3dDevice = nullptr; }
}

static LRESULT WINAPI WndProc(HWND hWnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    if (ImGui_ImplWin32_WndProcHandler(hWnd, msg, wParam, lParam)) return true;
    switch (msg) {
    case WM_SIZE:
        if (g_pd3dDevice && wParam != SIZE_MINIMIZED) {
            CleanupRenderTarget();
            g_pSwapChain->ResizeBuffers(0, LOWORD(lParam), HIWORD(lParam), DXGI_FORMAT_UNKNOWN, 0);
            CreateRenderTarget();
        }
        return 0;
    case WM_SYSCOMMAND:
        if ((wParam & 0xfff0) == SC_KEYMENU) return 0;
        break;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    case WM_CLOSE:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hWnd, msg, wParam, lParam);
}

static void loadFonts(ImGuiIO& io) {
    ImFontConfig cfg{};
    cfg.FontDataOwnedByAtlas = false;

    static const ImWchar interRanges[] = {
        0x0020, 0x00FF, // Basic Latin + Latin-1 Supplement (ç ö ü etc.)
        0x0100, 0x017F, // Latin Extended-A (ş ı ğ İ Ş Ğ)
        0,
    };

    const float inter_sizes[6] = { 14.f, 30.f, 14.f, 12.f, 12.f, 38.f };
    for (int i = 0; i < 6; i++) {
        const void* data = (i == 1 || i == 5) ? (const void*) inter_bold_hex
                          : (i == 4)          ? (const void*) inter_semibold_hex
                                              : (const void*) inter_medium_hex;
        int size = (i == 1 || i == 5) ? (int) sizeof(inter_bold_hex)
                  : (i == 4)          ? (int) sizeof(inter_semibold_hex)
                                      : (int) sizeof(inter_medium_hex);
        var->font.inter[i] = io.Fonts->AddFontFromMemoryTTF((void*) data, size, inter_sizes[i], &cfg, interRanges);
    }

    const float icon_sizes[5] = { 22.f, 14.f, 22.f, 46.f, 22.f };
    static const ImWchar iconRanges[] = { 0x0020, 0x00FF, 0 };
    for (int i = 0; i < 5; i++) {
        var->font.icons[i] = io.Fonts->AddFontFromMemoryTTF((void*) icons_hex,
                             (int) sizeof(icons_hex), icon_sizes[i], &cfg, iconRanges);
    }
}

int APIENTRY WinMain(HINSTANCE hInstance, HINSTANCE, LPSTR, int) {
    WNDCLASSEXW wc = { sizeof(wc), CS_CLASSDC, WndProc, 0L, 0L, hInstance,
                       nullptr, LoadCursor(nullptr, IDC_ARROW), nullptr, nullptr,
                       L"KiwiiLauncher", nullptr };
    RegisterClassExW(&wc);

    int W = (int) var->window.size.x;
    int H = (int) var->window.size.y;
    int screenW = GetSystemMetrics(SM_CXSCREEN);
    int screenH = GetSystemMetrics(SM_CYSCREEN);
    HWND hwnd = CreateWindowExW(0, wc.lpszClassName, L"Kiwii",
                                WS_POPUP, (screenW - W) / 2, (screenH - H) / 2, W, H,
                                nullptr, nullptr, wc.hInstance, nullptr);
    HRGN rgn = CreateRoundRectRgn(0, 0, W + 1, H + 1, 20, 20);
    SetWindowRgn(hwnd, rgn, TRUE);

    if (!CreateDeviceD3D(hwnd)) { CleanupDeviceD3D(); UnregisterClassW(wc.lpszClassName, wc.hInstance); return 1; }

    ShowWindow(hwnd, SW_SHOWDEFAULT);
    UpdateWindow(hwnd);

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;
    CreateDirectoryA("C:\\kiwii", nullptr);
    CreateDirectoryA("C:\\kiwii\\configs", nullptr);
    static const char* kIniPath = "C:\\kiwii\\configs\\imgui.ini";
    io.IniFilename = kIniPath;

    loadFonts(io);

    ImGui_ImplWin32_Init(hwnd);
    ImGui_ImplDX11_Init(g_pd3dDevice, g_pd3dDeviceContext);

    var->winapi.hwnd = hwnd;
    GetWindowRect(hwnd, &var->winapi.rc);
    var->window.glow_texture[0] = nullptr;
    var->window.glow_texture[1] = nullptr;

    bool running = true;
    while (running) {
        MSG msg;
        while (PeekMessage(&msg, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&msg);
            DispatchMessage(&msg);
            if (msg.message == WM_QUIT) running = false;
        }
        if (!running) break;

        ImGui_ImplDX11_NewFrame();
        ImGui_ImplWin32_NewFrame();
        ImGui::NewFrame();

        gui->render();

        ImGui::Render();
        const float clear[4] = { 0.0f, 0.0f, 0.0f, 0.0f };
        g_pd3dDeviceContext->OMSetRenderTargets(1, &g_mainRenderTargetView, nullptr);
        g_pd3dDeviceContext->ClearRenderTargetView(g_mainRenderTargetView, clear);
        ImGui_ImplDX11_RenderDrawData(ImGui::GetDrawData());
        g_pSwapChain->Present(1, 0);
    }

    ImGui_ImplDX11_Shutdown();
    ImGui_ImplWin32_Shutdown();
    ImGui::DestroyContext();
    CleanupDeviceD3D();
    DestroyWindow(hwnd);
    UnregisterClassW(wc.lpszClassName, wc.hInstance);
    return 0;
}
