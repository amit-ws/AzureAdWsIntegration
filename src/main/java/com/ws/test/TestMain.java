//package com.ws.test;
//
////public class TestMain {
////
////    public static void main(String[] args) {
////        Test t = new Test();
////        t.setName("");
////        t.setTestField("");
////        t.setMob(1);
////
////        Test t2 = Test.builder().testField("").mob(1).build();
////    }
////}
//
//import java.net.URLEncoder;
//import java.nio.charset.StandardCharsets;
//
//public class TestMain {
////    private static String generateAzureSignInUrl(String accessToken) {
////        try {
////            String azurePortalUrl = "https://portal.azure.com";
////
////            // Construct the URL with OAuth token for seamless sign-in
////            String signInUrl = azurePortalUrl + "/?auth_type=access_token&access_token="
////                    + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
////
////            return signInUrl;
////        } catch (Exception e) {
////            System.err.println("Error generating Azure Sign-In URL: " + e.getMessage());
////        }
////        return null;
////    }
//
//    private static String generateAzureSignInUrl(String idToken) {
//        try {
//            String azurePortalUrl = "https://portal.azure.com";
//
//            // Construct the SSO URL using ID token
//            String signInUrl = azurePortalUrl + "/?id_token="
//                    + URLEncoder.encode(idToken, StandardCharsets.UTF_8)
//                    + "&session_mode=always";
//
//            return signInUrl;
//        } catch (Exception e) {
//            System.err.println("Error generating Azure Sign-In URL: " + e.getMessage());
//        }
//        return null;
//    }
//
//    public static void main(String[] args) {
//        System.out.println(generateAzureSignInUrl("eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsImtpZCI6IllUY2VPNUlKeXlxUjZqekRTNWlBYnBlNDJKdyJ9.eyJhdWQiOiI5YWNhY2FmNi0wMmUxLTRlMDYtODRkOS01ZGE0YTdmZmQyYWEiLCJpc3MiOiJodHRwczovL2xvZ2luLm1pY3Jvc29mdG9ubGluZS5jb20vMDBiMWQwNmItZTMxNi00NWFmLWE2ZDItMjczNGY2MmE1YWNkL3YyLjAiLCJpYXQiOjE3MzgzMjIzNjYsIm5iZiI6MTczODMyMjM2NiwiZXhwIjoxNzM4MzI2MjY2LCJhaW8iOiJBZFFBSy84WkFBQUFtb1pSZUVGN0p5UWNJYVNLRGNlUEtqL254VFFvalVWTXFGdGdrRUV1Z3FxU2tuT2NTbDBMRGllNFdnVndyZExYbUVTNXBsYy9JRTZLTm9hNXhjYURJU1NORXJnRElFS09WNnpNcXJZT0Qyc0JkUHRUcDlCZEJoM1hYUjFJUnc1QUdQb0RUN3U3TjRwY200bTFDS1FteWpqUUtJK2RrZytrWGxkS09mVVA3NWMyOWQ5aFA3WGhRY3hZN3FyZUI0T1NSUWN2YjkrSElJQXJ3NnI0OFlmY2UrRSt6ODJqSEx6K21WQzMvVVg2ZDM5SEZlSWlBUlZZQ1hEUExsVnBoMU1ueFE1V0paZmorMllHWnR4RXdEWWxvdz09IiwiZW1haWwiOiJhbWl0QHdoaXRlc3dhbnNlY3VyaXR5LmNvbSIsImlkcCI6Imh0dHBzOi8vc3RzLndpbmRvd3MubmV0LzNjMTBjOTQxLTM3ZTQtNGIwMy04ZDk3LWQzNTI0YWJlNjA0MC8iLCJuYW1lIjoiQW1pdCBQcmFrYXNoIiwibm9uY2UiOiI0MDNkOWM2YS02OTM0LTRkOTEtOTcxNS05NTA5NzhmNmI1OGUiLCJvaWQiOiJlODE5MzRjMS0zODJhLTQxZmUtOTFiNS00YjNmODY0YmE1ODAiLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJhbWl0QHdoaXRlc3dhbnNlY3VyaXR5LmNvbSIsInJoIjoiMS5BWEVCYTlDeEFCYmpyMFdtMGljMDlpcGF6ZmJLeXByaEFnWk9oTmxkcEtmXzBxcHhBZTl4QVEuIiwicm9sZXMiOlsiQWRtaW4iXSwic2lkIjoiMTNjNDdlMDQtNWY2ZC00ZWE5LWE4MmItYTY0MDc2NGUzZTQyIiwic3ViIjoiVGtVaTRkc2dLcVRVSDhHekNXOWxzX1k3OFZsSFVZbHhYWm9kX2wxdjYtVSIsInRpZCI6IjAwYjFkMDZiLWUzMTYtNDVhZi1hNmQyLTI3MzRmNjJhNWFjZCIsInV0aSI6InRJZWtoQlM4NTAyMFoxZERqMWFVQUEiLCJ2ZXIiOiIyLjAifQ.UvBRPZJysyrQT-Rk_orVs-OqemLREfEpZ3yeZc-B7cWru15PfaFgZbZHS6TKUf-9YQuqQ3JhND4pklD92SSianWzKnP5_u2jg-hxrQoVyG3dJmg4oUvhYrLTcDH_kbBJ64ZgUy0AyIfHC-LE82aEJKHX64e2iUPtBXnrKGtwr4kRD76rvfAhgWscqAz58onXLIrd4kqw1UeknLia_VKZvMxUI1srEHaVEc9N7n0aczmUuiwcEQMxenbZ1ai46G1a-l0KhEx4kdmmXcQwxkK3NR_y339fWwXpGNkaecIW4PrA_1SXdTvG-FksAczzuPRZm24RGuDGzqbimmWwgXn9Pw"));
//
//    }
//}