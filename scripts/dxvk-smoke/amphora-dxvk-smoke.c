/*
 * amphora-dxvk-smoke: D3D11 (DXVK) 3D smoke for the AHB swapchain path.
 * Opens a captioned 656x519 window at (100,100), sized for a 640x480 client
 * with Windows frame metrics (under Wine the client area is 648x485),
 * clears the back buffer to magenta (217,26,178) and presents FRAMES (175)
 * times with sync interval 0, reading the back buffer back at
 * frames 0/1/5/25/50/100. Log: C:\amphora-dxvk-smoke.log. The host side logs
 * AHB_SC create/destroy (presents=, fenced=) and guest-readback CLASS=.
 *
 * Build (macOS/Linux, mingw-w64):
 *   x86_64-w64-mingw32-gcc -O2 -s -o amphora-dxvk-smoke.exe \
 *     amphora-dxvk-smoke.c -ld3d11 -ldxgi -luser32
 * Push and run (see docs/05 §5 for the pass criteria):
 *   adb push amphora-dxvk-smoke.exe /data/local/tmp/ &&
 *   adb shell run-as app.amphora sh -c \
 *     'mkdir -p files/exe && cp /data/local/tmp/amphora-dxvk-smoke.exe files/exe/'
 *   adb shell am start -n app.amphora/.MainActivity \
 *     --ez app.amphora.debug.WINEANDROID true \
 *     --es app.amphora.debug.WINE_EXE /data/user/0/app.amphora/files/exe/amphora-dxvk-smoke.exe
 */
#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <stdio.h>
#include <stdint.h>
#include <stdarg.h>
#include <string.h>

/* mingw sometimes needs explicit IID */
static const GUID kIID_ID3D11Texture2D =
{0x6f15aaf2,0xd208,0x4e89,{0x9a,0xb4,0x48,0x95,0x35,0xd3,0x4f,0x9c}};

static FILE *g_log;

static void slog(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    if (g_log) { vfprintf(g_log, fmt, ap); fflush(g_log); }
    va_end(ap);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
}

static const char *class_rgba(int r, int g, int b)
{
    if (r > 180 && b > 150 && g < 80) return "MAGENTA";
    if (r < 40 && g < 40 && b < 40) return "BLACK";
    if (r > 200 && g > 200 && b > 200) return "WHITE";
    return "OTHER";
}

static void d3d_readback(ID3D11Device *dev, ID3D11DeviceContext *ctx,
                         ID3D11Texture2D *bb, UINT w, UINT h, int frame, const char *when)
{
    D3D11_TEXTURE2D_DESC td;
    ID3D11Texture2D *staging = NULL;
    D3D11_MAPPED_SUBRESOURCE map;
    HRESULT hr;
    const uint8_t *p;
    int r, g, b, a;
    ID3D11Texture2D_GetDesc(bb, &td);
    td.Usage = D3D11_USAGE_STAGING;
    td.BindFlags = 0;
    td.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    td.MiscFlags = 0;
    hr = ID3D11Device_CreateTexture2D(dev, &td, NULL, &staging);
    if (FAILED(hr) || !staging) return;
    ID3D11DeviceContext_CopyResource(ctx, (ID3D11Resource *)staging, (ID3D11Resource *)bb);
    hr = ID3D11DeviceContext_Map(ctx, (ID3D11Resource *)staging, 0, D3D11_MAP_READ, 0, &map);
    if (SUCCEEDED(hr)) {
        p = (const uint8_t *)map.pData + (size_t)(h / 2) * map.RowPitch + (size_t)(w / 2) * 4u;
        b = p[0]; g = p[1]; r = p[2]; a = p[3];
        slog("d3d-readback when=%s frame=%d centerRGBA=%d,%d,%d,%d CLASS=%s\n",
             when, frame, r, g, b, a, class_rgba(r, g, b));
        ID3D11DeviceContext_Unmap(ctx, (ID3D11Resource *)staging, 0);
    }
    ID3D11Texture2D_Release(staging);
}

static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam)
{
    if (msg == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcA(hwnd, msg, wParam, lParam);
}

int WINAPI WinMain(HINSTANCE hi, HINSTANCE hp, LPSTR cmd, int show)
{
    WNDCLASSA wc;
    HWND hwnd;
    DXGI_SWAP_CHAIN_DESC sd;
    IDXGISwapChain *sc = NULL;
    ID3D11Device *dev = NULL;
    ID3D11DeviceContext *ctx = NULL;
    ID3D11Texture2D *bb = NULL;
    ID3D11RenderTargetView *rtv = NULL;
    D3D_FEATURE_LEVEL fl;
    HRESULT hr;
    float clear[4] = { 217.f/255.f, 26.f/255.f, 178.f/255.f, 1.f };
    int frame;
    const int FRAMES = 175;
    UINT w = 640, h = 480;
    D3D11_TEXTURE2D_DESC bbdesc;
    MSG msg;
    (void)hp; (void)cmd; (void)show;

    g_log = fopen("C:\\amphora-dxvk-smoke.log", "w");
    slog("dxvk-smoke start\n");
    slog("knife9-style long Present + magenta clear (FRAMES=%d)\n", FRAMES);
    slog("LoadLibrary dxgi.dll => %p\n", (void *)LoadLibraryA("dxgi.dll"));
    slog("LoadLibrary d3d11.dll => %p\n", (void *)LoadLibraryA("d3d11.dll"));

    memset(&wc, 0, sizeof(wc));
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hi;
    wc.lpszClassName = "AmphoraDxvkSmoke";
    RegisterClassA(&wc);
    hwnd = CreateWindowA("AmphoraDxvkSmoke", "Amphora DXVK Smoke",
                         WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                         100, 100, (int)w + 16, (int)h + 39, NULL, NULL, hi, NULL);
    slog("hwnd=%p\n", (void *)hwnd);
    Sleep(200);
    slog("after surface wait\n");

    memset(&sd, 0, sizeof(sd));
    sd.BufferCount = 2;
    sd.BufferDesc.Width = w;
    sd.BufferDesc.Height = h;
    sd.BufferDesc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.OutputWindow = hwnd;
    sd.SampleDesc.Count = 1;
    sd.Windowed = TRUE;
    sd.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;

    hr = D3D11CreateDeviceAndSwapChain(NULL, D3D_DRIVER_TYPE_HARDWARE, NULL, 0,
                                       NULL, 0, D3D11_SDK_VERSION, &sd,
                                       &sc, &dev, &fl, &ctx);
    slog("D3D11CreateDeviceAndSwapChain hr=0x%08lx fl=0x%x sc=%p dev=%p ctx=%p\n",
         (unsigned long)hr, (unsigned)fl, (void *)sc, (void *)dev, (void *)ctx);
    if (FAILED(hr)) goto out;

    hr = IDXGISwapChain_GetBuffer(sc, 0, &kIID_ID3D11Texture2D, (void **)&bb);
    slog("GetBuffer hr=0x%08lx bb=%p\n", (unsigned long)hr, (void *)bb);
    if (FAILED(hr)) goto out;
    hr = ID3D11Device_CreateRenderTargetView(dev, (ID3D11Resource *)bb, NULL, &rtv);
    slog("CreateRTV hr=0x%08lx rtv=%p\n", (unsigned long)hr, (void *)rtv);
    if (FAILED(hr)) goto out;
    ID3D11Texture2D_GetDesc(bb, &bbdesc);
    slog("bb GetDesc %ux%u fmt=%u\n", bbdesc.Width, bbdesc.Height, (unsigned)bbdesc.Format);
    w = bbdesc.Width; h = bbdesc.Height;

    for (frame = 0; frame < FRAMES; frame++) {
        while (PeekMessageA(&msg, NULL, 0, 0, PM_REMOVE)) {
            if (msg.message == WM_QUIT) goto out;
            TranslateMessage(&msg);
            DispatchMessageA(&msg);
        }
        ID3D11DeviceContext_OMSetRenderTargets(ctx, 1, &rtv, NULL);
        ID3D11DeviceContext_ClearRenderTargetView(ctx, rtv, clear);
        if (frame == 0 || frame == 1 || frame == 5 || frame == 25 || frame == 50 || frame == 100)
            d3d_readback(dev, ctx, bb, w, h, frame, "pre-present");
        hr = IDXGISwapChain_Present(sc, 0, 0);
        if (frame < 8 || frame % 25 == 0)
            slog("Present frame=%d hr=0x%08lx\n", frame, (unsigned long)hr);
        if (frame == 25)
            slog("dxvk-smoke screencap-ready\n");
        if (FAILED(hr)) {
            slog("Present failed frame=%d hr=0x%08lx — stop\n", frame, (unsigned long)hr);
            break;
        }
    }

out:
    slog("dxvk-smoke done\n");
    if (rtv) ID3D11RenderTargetView_Release(rtv);
    if (bb) ID3D11Texture2D_Release(bb);
    if (ctx) ID3D11DeviceContext_Release(ctx);
    if (dev) ID3D11Device_Release(dev);
    if (sc) IDXGISwapChain_Release(sc);
    if (g_log) fclose(g_log);
    return 0;
}
