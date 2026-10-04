// LiquidHub 音乐源插件：网易云。
// 使用方式：设置 > 其他 > 音乐源 > 添加音乐源 > 写名称 > 导入本目录的 manifest.json 和 index.js > 测试 > 启用。
// 注意：请把下面的 COOKIE 替换为你自己的网易云登录 Cookie（MUSIC_U 等），否则搜索可能因风控返回空。

var BASE_KEY = "0CoJUm6Qyw8W8jud";
var IV = "0102030405060708";
var PUB_MOD = "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
var PUB_EXP = "010001";
var CHARSET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
var COOKIE = "";

function randSecret() {
  var s = "";
  for (var i = 0; i < 16; i++) s += CHARSET.charAt(Math.floor(Math.random() * CHARSET.length));
  return s;
}

function aes(text, key) {
  return __aesCbcBase64(text, key, IV);
}

function weapi(obj) {
  var secret = randSecret();
  var params = aes(aes(JSON.stringify(obj), BASE_KEY), secret);
  var reversed = secret.split("").reverse().join("");
  var encSecKey = __rsaEncryptHex(reversed, PUB_MOD, PUB_EXP);
  return "params=" + encodeURIComponent(params) + "&encSecKey=" + encodeURIComponent(encSecKey);
}

function post(path, obj) {
  var body = weapi(obj);
  var headers = {
    "Content-Type": "application/x-www-form-urlencoded",
    "Referer": "https://music.163.com/",
    "Origin": "https://music.163.com",
    "User-Agent": UA
  };
  if (COOKIE) headers["Cookie"] = COOKIE;
  return __http("POST", "https://music.163.com" + path, JSON.stringify(headers), body);
}

function search(keyword) {
  var res = post("/weapi/cloudsearch/get/web?csrf_token=", {
    s: keyword,
    type: 1,
    limit: 30,
    offset: 0,
    total: true
  });
  var songs = (JSON.parse(res).result || {}).songs || [];
  return JSON.stringify(songs.map(function (s) {
    var ar = (s.ar || s.artists || []).map(function (a) { return a.name; }).join("/");
    var al = s.al || s.album || {};
    return {
      id: String(s.id),
      name: s.name,
      artist: ar,
      album: al.name || "",
      cover: (al.picUrl || "").replace("http://", "https://")
    };
  }));
}

function songUrl(id) {
  var res = post("/weapi/song/enhance/player/url/v1?csrf_token=", {
    ids: JSON.stringify([String(id)]),
    level: "standard",
    encodeType: "aac"
  });
  var data = (JSON.parse(res).data || [])[0] || {};
  return data.url || "";
}

function lyrics(id) {
  var res = post("/weapi/song/lyric?csrf_token=", {
    id: String(id),
    lv: -1,
    kv: -1,
    tv: -1
  });
  var j = JSON.parse(res);
  var lrc = (j.lrc || {}).lyric || "";
  var tr = (j.tlyric || {}).lyric || "";
  return JSON.stringify({ lyric: lrc, translation: tr });
}
