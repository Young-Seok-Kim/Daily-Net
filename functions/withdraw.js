/**
 * 회원탈퇴. 사용자 데이터 전부와 Auth 계정을 서버가 지운다.
 *
 * 예전에는 앱이 Firestore의 users/{uid} 문서를 직접 지우고 Auth 계정을 스스로 삭제했다.
 * 그런데 Firestore는 문서를 지워도 그 밑의 서브컬렉션(settlements, settings 등)을 같이
 * 지우지 않는다. 클라이언트 SDK는 서브컬렉션 목록을 읽을 수도 없어서, 탈퇴한 사용자의
 * 기록이 "필드 없는 문서" 밑에 그대로 남았다(2026-11-18 콘솔에서 확인, 탈퇴자 2명분).
 * 탈퇴 화면이 "모든 기록이 영구 삭제된다"고 약속하는데 실제로는 남아 있던 셈이다.
 *
 * 그래서 Admin SDK의 recursiveDelete로 서버가 통째로 지운다. 덤으로
 *   - 앱의 "최근 로그인 필요(CREDENTIAL_TOO_OLD)" 오류가 사라진다. Auth 삭제도 서버가 한다.
 *   - 구매 토큰 → uid 매핑(purchase_tokens)도 같이 지운다. 안 지우면 나중에 Play RTDN이
 *     왔을 때 playSubscriptionEvent가 없는 사용자 문서에 구독 상태를 써서 유령 문서가 되살아난다.
 *
 * 순서는 Firestore → 토큰 매핑 → Auth 다. Auth를 먼저 지우면 중간에 실패했을 때
 * 사용자가 다시 로그인해 재시도할 수 없다. 반대로 Firestore가 먼저 지워진 채 실패하면
 * 다시 눌러서 이어가면 된다(recursiveDelete는 비어 있어도 성공한다).
 *
 * 구버전 앱(b60 이하)은 여전히 문서를 직접 지우므로 firestore.rules의 users delete 허용은 당분간 둔다.
 *
 * ⚠️ 함수 실행 계정(기본 compute 서비스 계정)에 "Firebase Authentication 관리자"
 * (roles/firebaseauth.admin) 역할이 있어야 Auth 계정을 지울 수 있다. 없으면 Firestore는 지워진 뒤
 * Auth 삭제에서 insufficient permission 으로 500이 난다 (2026-11-18 첫 배포 때 겪음).
 * 새 프로젝트에 올릴 때는 아래 한 줄을 먼저 실행한다:
 *   gcloud projects add-iam-policy-binding <project>
 *     --member=serviceAccount:<project-number>-compute@developer.gserviceaccount.com
 *     --role=roles/firebaseauth.admin
 */
const { onRequest } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const { verifyUser } = require("./quota");
const { rejectNonAppRequest } = require("./requestGuard");

if (admin.apps.length === 0) {
    admin.initializeApp();
}

/** subscription.js의 TOKEN_MAP과 같은 이름. 순환 참조를 피하려고 여기서 다시 적는다. */
const TOKEN_MAP = "purchase_tokens";

/** 해당 uid를 가리키는 구매 토큰 매핑을 전부 지운다. 지운 개수를 돌려준다. */
async function deleteTokenMappings(db, uid) {
    const snap = await db.collection(TOKEN_MAP).where("uid", "==", uid).get();
    if (snap.empty) return 0;
    const batch = db.batch();
    snap.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
    return snap.size;
}

exports.withdrawAccount = onRequest({
    region: "asia-northeast3",
    cors: true,
    enforceAppCheck: true,
    // 기록이 수백 건이어도 recursiveDelete는 수 초면 끝나지만, 콜드 스타트까지 감안해 넉넉히 둔다.
    timeoutSeconds: 60
}, async (req, res) => {
    if (rejectNonAppRequest(req, res)) return;

    const user = await verifyUser(req);
    if (!user) {
        return res.status(401).json({ ok: false, error: "unauthenticated" });
    }

    // 본문에 confirm: true 가 없으면 거절한다. 토큰이 새어 나가도 이 한 번의 POST로
    // 계정이 날아가는 일은 없게 하려는 최소한의 안전장치다.
    if (!req.body || req.body.confirm !== true) {
        return res.status(400).json({ ok: false, error: "confirm required" });
    }

    const uid = user.uid;
    try {
        const db = admin.firestore();
        await db.recursiveDelete(db.collection("users").doc(uid));
        const tokens = await deleteTokenMappings(db, uid);
        await admin.auth().deleteUser(uid);

        console.log(`회원탈퇴: uid=${uid} email=${user.email} 토큰매핑=${tokens}`);
        res.status(200).json({ ok: true });
    } catch (error) {
        console.error(`회원탈퇴 실패: uid=${uid}`, error.message);
        res.status(500).json({ ok: false, error: "withdraw failed" });
    }
});
