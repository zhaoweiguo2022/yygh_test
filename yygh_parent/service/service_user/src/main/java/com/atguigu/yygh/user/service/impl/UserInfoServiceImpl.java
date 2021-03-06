package com.atguigu.yygh.user.service.impl;

import com.atguigu.yygh.common.exception.YyghException;
import com.atguigu.yygh.common.helper.JwtHelper;
import com.atguigu.yygh.common.result.ResultCodeEnum;
import com.atguigu.yygh.enums.AuthStatusEnum;
import com.atguigu.yygh.model.acl.User;
import com.atguigu.yygh.model.user.Patient;
import com.atguigu.yygh.model.user.UserInfo;
import com.atguigu.yygh.user.mapper.UserInfoMapper;
import com.atguigu.yygh.user.service.PatientService;
import com.atguigu.yygh.user.service.UserInfoService;
import com.atguigu.yygh.vo.user.LoginVo;
import com.atguigu.yygh.vo.user.UserAuthVo;
import com.atguigu.yygh.vo.user.UserInfoQueryVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements UserInfoService {

    @Autowired
    private RedisTemplate<String,String> redisTemplate;

    @Autowired
    private PatientService patientService;
    /**
     * 用户进行登录
     * @param loginVo
     * @return
     */
    @Override
    public Map<String, Object> loginUser(LoginVo loginVo) {
        String phone = loginVo.getPhone();
        String code = loginVo.getCode();
        //校验参数
        if(StringUtils.isEmpty(phone) || StringUtils.isEmpty(code)){
            throw new YyghException(ResultCodeEnum.PARAM_ERROR);
        }

        String redisCode = redisTemplate.opsForValue().get(phone);
        if(!code.equals(redisCode)){
            //验证码错误
            throw new YyghException(ResultCodeEnum.CODE_ERROR);
        }

        //绑定手机号码
        UserInfo userInfo = null;
        if(!StringUtils.isEmpty(loginVo.getOpenid())) {
            userInfo = this.selectWxInfoOpenId(loginVo.getOpenid());
            if(null != userInfo) {
                userInfo.setPhone(loginVo.getPhone());
                this.updateById(userInfo);
            } else {
                throw new YyghException(ResultCodeEnum.DATA_ERROR);
            }
        }

        if(userInfo == null){
            //手机绑定
            //查看该手机号是否登录过
            QueryWrapper<UserInfo> wrapper = new QueryWrapper<>();
            wrapper.eq("phone", phone);
            userInfo = baseMapper.selectOne(wrapper);
            if(userInfo == null){
                //第一次使用这个手机号进行登录
                //添加到数据库
                userInfo = new UserInfo();
                userInfo.setName("");
                userInfo.setPhone(phone);
                userInfo.setStatus(1);
                baseMapper.insert(userInfo);
            }
        }

        //校验是否被禁用
        if(userInfo.getStatus() == 0){
            throw new YyghException(ResultCodeEnum.LOGIN_DISABLED_ERROR);
        }

        //返回页面显示名称
        Map<String, Object> map = new HashMap<>();
        String name = userInfo.getName();
        if(StringUtils.isEmpty(name)) {
            name = userInfo.getNickName();
        }
        if(StringUtils.isEmpty(name)) {
            name = userInfo.getPhone();
        }
        map.put("name", name);
        //生成token
        String token = JwtHelper.createToken(userInfo.getId(), name);

        map.put("token", token);
        return map;
    }

    /**
     * 根据openid查询用户信息
     * @param openid
     * @return
     */
    @Override
    public UserInfo selectWxInfoOpenId(String openid) {
        QueryWrapper<UserInfo> wrapper = new QueryWrapper<>();
        wrapper.eq("openid", openid);
        UserInfo userInfo = baseMapper.selectOne(wrapper);
        return userInfo;
    }

    /**
     * 用户根据id, userAuthvo进行认证
     * @param userId
     * @param userAuthVo
     */
    @Override
    public void userAuth(Long userId, UserAuthVo userAuthVo) {
        //根据用户id查询用户信息
        UserInfo userInfo = baseMapper.selectById(userId);
        //设置认证信息
        //认证人姓名
        userInfo.setName(userAuthVo.getName());
        //其他信息
        userInfo.setCertificatesType(userAuthVo.getCertificatesType());
        userInfo.setCertificatesNo(userAuthVo.getCertificatesNo());
        userInfo.setCertificatesUrl(userAuthVo.getCertificatesUrl());
        userInfo.setAuthStatus(AuthStatusEnum.AUTH_RUN.getStatus());

        //更新数据库
        baseMapper.updateById(userInfo);
    }

    /**
     * 用户列表（条件加分页）
     * @param pageParm
     * @param userInfoQueryVo
     * @return
     */
    @Override
    public IPage<UserInfo> selectPage(Page<UserInfo> pageParm, UserInfoQueryVo userInfoQueryVo) {
        if(userInfoQueryVo==null){
            return null;
        }
        //UserInfoQueryVo获取条件值
        String name = userInfoQueryVo.getKeyword(); //用户名称
        Integer status = userInfoQueryVo.getStatus();//用户状态
        Integer authStatus = userInfoQueryVo.getAuthStatus(); //认证状态
        String createTimeBegin = userInfoQueryVo.getCreateTimeBegin(); //开始时间
        String createTimeEnd = userInfoQueryVo.getCreateTimeEnd(); //结束时间

        //对条件值进行非空判断
        QueryWrapper<UserInfo> wrapper = new QueryWrapper<>();
        if(!StringUtils.isEmpty(name)) {
            wrapper.like("name",name);
        }
        if(!StringUtils.isEmpty(status)) {
            wrapper.eq("status",status);
        }
        if(!StringUtils.isEmpty(authStatus)) {
            wrapper.eq("auth_status",authStatus);
        }
        if(!StringUtils.isEmpty(createTimeBegin)) {
            wrapper.ge("create_time",createTimeBegin);
        }
        //条件查询，begin -> end 这个区间创建的用户
        if(!StringUtils.isEmpty(createTimeEnd)) {
            wrapper.le("create_time",createTimeEnd);
        }

        //调用mapper方法
        Page<UserInfo> pages = baseMapper.selectPage(pageParm, wrapper);
        //编号变成对应值的封装
        pages.getRecords().stream().forEach(item ->{
            this.packageUserInfo(item);
        });
        return pages;
    }

    /**
     * 根据用户id 更改用户锁定状态
     * @param userId
     * @param status
     */
    @Override
    public void lock(Long userId, Integer status) {
        if (status.intValue()==0 || status.intValue()==1){
            UserInfo userInfo = baseMapper.selectById(userId);
            userInfo.setStatus(status);
            baseMapper.updateById(userInfo);
        }
    }

    /**
     * 展示某用户详情接口
     * @param userId
     * @return
     */
    @Override
    public Map<String, Object> show(Long userId) {
        Map<String,Object> map = new HashMap<>();
        //根据userid查询用户信息
        UserInfo userInfo = this.packageUserInfo(baseMapper.selectById(userId));
        map.put("userInfo",userInfo);

        //根据用户信息查询就诊人信息
        List<Patient> patientList = patientService.findAllByUserId(userId);
        map.put("patientList",patientList);
        return map;
    }

    /**
     * 根据id对用户认证审批
     * @param userId
     * @param authStatus
     */
    @Override
    public void approve(Long userId, Integer authStatus) {
        if(authStatus.intValue()==2 || authStatus==-1){
            //2是通过，-1是不通过
            UserInfo userInfo = baseMapper.selectById(userId);
            userInfo.setAuthStatus(authStatus);
            baseMapper.updateById(userInfo);
        }
    }

    /**
     * 根据用户认证的姓名，模糊查询叫该用户的所用用户列表
     * @param username
     * @return
     */
    @Override
    public List<UserInfo> findUserListByUserName(String username) {
        QueryWrapper<UserInfo> wrapper = new QueryWrapper<>();
        wrapper.like("name",username);
        List<UserInfo> userInfoList = baseMapper.selectList(wrapper);
        return userInfoList;
    }

    /**
     * 将编号变成对应着的封装，例如status = 0代表锁定
     * @param userInfo
     * @return
     */
    private UserInfo packageUserInfo(UserInfo userInfo){
        //处理认证状态编码
        userInfo.getParam().put("authStatusString", AuthStatusEnum.getStatusNameByStatus(userInfo.getAuthStatus()));
        //处理用户状态
        String statusString =  userInfo.getStatus().intValue()==0?"锁定":"正常";
        userInfo.getParam().put("statusString",statusString);
        return userInfo;
    }
}
