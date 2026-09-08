local count = tonumber(app.storage_get("open_count") or "0") + 1
app.storage_set("open_count", tostring(count))

return ui.page({
    title = "Lua 示例",
    children = {
        ui.text({
            text = "这是服务器动态下发的 Oldchat 小程序页面。",
            size = 18,
            color = "#6FACC9",
            center = true,
            margin = 8
        }),
        ui.text({
            text = "本页面已打开 " .. tostring(count) .. " 次。Lua 可以创建原生控件、加载网络图片、请求 Oldchat API，并在权限允许时调用相机。",
            size = 14,
            margin = 8
        }),
        ui.image({
            id = "preview",
            url = "https://oc.mcl0.dpdns.org/app-assets/oldchat-icon.png",
            height = 160,
            margin = 8
        }),
        ui.button({
            text = "拍照",
            on_click = function()
                app.camera(function(uri, err)
                    if uri then
                        app.storage_set("last_photo", uri)
                        app.set_image("preview", uri)
                        app.toast("拍照完成，页面图片已更新")
                    else
                        app.toast(err or "拍照失败")
                    end
                end)
            end
        }),
        ui.button({
            text = "测试服务器请求",
            on_click = function()
                app.http_get("/me", function(body, err)
                    if body then app.toast("服务器请求成功")
                    else app.toast(err or "请求失败") end
                end)
            end
        })
    }
})
