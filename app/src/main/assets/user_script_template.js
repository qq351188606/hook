/**
 * 美团众包订单Hook - 用户脚本模板
 *
 * API:
 *   window.onOrder(json) - 订单捕获回调 (必须实现)
 *   window.grabResult    - 抢单结果 {shouldGrab, reason, order}
 */

var config = {
    minIncome: 8.0,
    maxDistance: 5000,
    minTip: 0,
    includeKeywords: [],
    excludeKeywords: [],
    merchantFilter: []
};

window.onOrder = function(json) {
    try {
        var order = JSON.parse(json);
        var data = order.data || order;
        var result = { shouldGrab: false, reason: '', order: order };

        var fee = parseFloat(data.deliveryFee || data.fee || data.income || 0);
        var tip = parseFloat(data.tip || 0);
        var subsidy = parseFloat(data.subsidy || 0);
        var total = fee + tip + subsidy;
        var dist = parseInt(data.deliveryDistance || data.distance || data.totalDistance || 0);

        var text = [data.poiName, data.shopName, data.poiAddress,
                   data.pickupAddress, data.deliveryAddress,
                   data.receiverName, data.merchantName, data.goodsName]
                   .filter(function(v){return v;}).join(' ').toLowerCase();

        if (total < config.minIncome) { result.reason = '金额不足:'+total; return finalize(result); }
        if (dist > config.maxDistance) { result.reason = '距离过远:'+dist+'m'; return finalize(result); }
        for (var i=0; i<config.excludeKeywords.length; i++) {
            if (config.excludeKeywords[i].toLowerCase() && text.indexOf(config.excludeKeywords[i].toLowerCase()) !== -1) {
                result.reason = '排除关键词:'+config.excludeKeywords[i]; return finalize(result);
            }
        }
        if (config.includeKeywords.length > 0) {
            var hit = config.includeKeywords.some(function(k){ return k && text.indexOf(k.toLowerCase()) !== -1; });
            if (!hit) { result.reason = '未命中白名单'; return finalize(result); }
        }

        result.shouldGrab = true;
        result.reason = '金额达标('+total.toFixed(2)+'元) 距离('+dist+'m)';
        return finalize(result);
    } catch(e) { return finalize({shouldGrab:false,reason:'JS异常:'+e.message,order:null}); }
};

function finalize(r) { window.grabResult = JSON.stringify(r); return window.grabResult; }

function log(msg) { try { android.util.Log.i('ZBHOOK', '[JS] '+String(msg)); } catch(e) {} }

// 腾讯文档API对接示例 (取消注释启用)
// var TX_API = { token: '', base: 'https://docs.qq.com/openapi' };
// function callTx(endpoint, data) { return httpPost(TX_API.base+endpoint, JSON.stringify(data)); }
// function httpPost(url, body) { try { var c = java.net.URL(url).openConnection(); c.setRequestMethod('POST'); c.setDoOutput(true); c.setConnectTimeout(3000); c.setReadTimeout(5000); var os=c.getOutputStream(); os.write(java.lang.String(body).getBytes('UTF-8')); os.flush(); os.close(); var r=new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(),'UTF-8')); var s=''; var l; while((l=r.readLine())!==null){s+=l+'\n';} r.close(); return s; } catch(e){return null;} }