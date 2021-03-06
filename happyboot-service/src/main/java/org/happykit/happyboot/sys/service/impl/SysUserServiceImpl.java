package org.happykit.happyboot.sys.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.happykit.happyboot.constant.SysConstant;
import org.happykit.happyboot.exception.SysException;
import org.happykit.happyboot.page.PageUtils;
import org.happykit.happyboot.sys.factory.SysUserFactory;
import org.happykit.happyboot.sys.mapper.SysUserMapper;
import org.happykit.happyboot.sys.model.entity.SysUserDO;
import org.happykit.happyboot.sys.model.form.SysUserForm;
import org.happykit.happyboot.sys.model.form.SysUserPwdForm;
import org.happykit.happyboot.sys.model.form.SysUserStatusForm;
import org.happykit.happyboot.sys.model.query.SysUserPageQueryParam;
import org.happykit.happyboot.sys.service.SysUserRelService;
import org.happykit.happyboot.sys.service.SysUserService;
import org.happykit.happyboot.sys.util.SysSecurityUtils;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户账号表 服务实现类
 *
 * @author shaoqiang
 * @version 1.0 2020/04/01
 */
@Validated
@Service
@CacheConfig(cacheNames = {"sysUser"})
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUserDO> implements SysUserService {

    private final PasswordEncoder passwordEncoder;
    private final SysSecurityUtils sysSecurityUtils;
    private final SysUserRelService sysUserRelService;

    public SysUserServiceImpl(PasswordEncoder passwordEncoder,
                              SysSecurityUtils sysSecurityUtils,
                              SysUserRelService sysUserRelService) {
        this.passwordEncoder = passwordEncoder;
        this.sysSecurityUtils = sysSecurityUtils;
        this.sysUserRelService = sysUserRelService;
    }

    @Override
    public SysUserDO getByUsername(String username) {
        return this.baseMapper.selectByUsername(username);
    }

    @Override
    public IPage<SysUserDO> listSysUsersByPage(SysUserPageQueryParam param) {
        // LambdaQueryWrapper<SysUserDO> lambdaQueryWrapper = new LambdaQueryWrapper();
        // if (StringUtils.isNotBlank(param.getAccount())) {
        // lambdaQueryWrapper.like(SysUserDO::getUsername, param.getAccount());
        // lambdaQueryWrapper.like(SysUserDO::getNickname, param.getAccount());
        // }
        // return this.page(page, lambdaQueryWrapper);
        Page page = new PageUtils<SysUserPageQueryParam>().page(param, SysUserDO.class);

        return this.baseMapper.selectSysUsersByPage(page, param);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysUserDO saveSysUser(SysUserForm form) {
        if (!form.getPassword().equals(form.getPasswordConfirm())) {
            throw new RuntimeException("两次密码不正确");
        }
        SysUserDO entity = SysUserFactory.INSTANCE.form2Do(form);
        entity.setId(null);
        entity.setPassword(passwordEncoder.encode(entity.getPassword()));

        // 判断用户名是否重复
        checkSysUserUniqueness(entity);

        // 保存基本信息
        this.save(entity);

        // 保存账号关联信息
        String userType = form.getUserType();
        List<String> userIdList = form.getUserIdList();
        if (SysConstant.USER_TYPE_1.equals(userType) && null != userIdList && userIdList.size() > 0) {
            sysUserRelService.addRel(userIdList.get(0), entity.getId());
        }
//        if (!save(entity)) {
//            throw new RuntimeException("保存失败");
//        }

        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysUserDO updateSysUser(SysUserForm form) {
        SysUserDO dbRecord = this.getById(form.getId());
        if (null == dbRecord) {
            throw new RuntimeException("未找到相关记录");
        }
        SysUserDO entity = SysUserFactory.INSTANCE.form2Do(form);

        // 更新基本信息
        this.updateById(entity);

//        if (!updateById(entity)) {
//            throw new RuntimeException("保存失败");
//        }

        // 保存账号关联信息
        String userId = dbRecord.getId();
        String userType = dbRecord.getUserType();
        List<String> userIdList = form.getUserIdList();
        sysUserRelService.delRel(userId, userType); // 删除关系
        if (SysConstant.USER_TYPE_0.equals(userType)) {
            if (null != userIdList && userIdList.size() > 0) {
                for (String id : userIdList) {
                    sysUserRelService.addRel(userId, id);
                }
            }
        }

        if (SysConstant.USER_TYPE_1.equals(userType)) {
            if (null != userIdList && userIdList.size() > 0) {
                sysUserRelService.addRel(userIdList.get(0), userId);
            }
        }

        return entity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSysUserPwd(SysUserPwdForm form) {
        if (!form.getPassword().equals(form.getPasswordConfirm())) {
            throw new RuntimeException("两次密码不正确");
        }
        SysUserDO entity = SysUserFactory.INSTANCE.pwdForm2Do(form);
        entity.setPassword(passwordEncoder.encode(entity.getPassword()));
        return this.retBool(this.baseMapper.updatePassword(entity));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSysUserStatus(SysUserStatusForm form) {
        List<SysUserDO> entityList = form.getIds().stream().map(record -> {
            SysUserDO entity = new SysUserDO();
            entity.setId(record);
            entity.setStatus(form.getStatus());
            return entity;
        }).collect(Collectors.toList());

        return this.updateBatchById(entityList);
    }

    @Override
    public boolean updateSysUserLoginInfo(SysUserDO entity) {
        return this.retBool(this.baseMapper.updateLoginInfo(entity));
    }

    @Override
    public boolean deleteSysUser(String id) {
        SysUserDO sysUser = this.getById(id);
        if (sysUser == null) {
            return false;
        }

        // 判断是否可以删除
        if (SysConstant.ADMIN_ACCOUNT.equals(sysUser.getUsername())) {
            throw new SysException("你在玩火~！");
        }
        return removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteSysUser(String... ids) {
//        List<String> idList = Arrays.asList(ids);
        for (String id : ids) {
            this.deleteSysUser(id);
        }
        return true;
    }

    /**
     * 判断用户名是否唯一
     *
     * @param sysUser
     */
    private void checkSysUserUniqueness(SysUserDO sysUser) {
        SysUserDO temp = this.baseMapper.selectByUsername(sysUser.getUsername());
        if (temp != null && !temp.getId().equals(sysUser.getId())) {
            throw new RuntimeException(String.format("当前用户名【%s】已经存在，请重新选择一个用户名", sysUser.getUsername()));
        }
    }

    @Override
    public List<SysUserDO> getMainAccountList(String keyword) {
        return this.baseMapper.getMainAccountList(keyword);
    }

    @Override
    public String getNicknameById(String id) {
        return this.baseMapper.getNicknameById(id);
    }
}